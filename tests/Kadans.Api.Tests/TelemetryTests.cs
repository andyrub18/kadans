using Kadans.Api;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.TestHost;
using Serilog;
using Serilog.Core;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Diagnostics.Metrics.Testing;
using Serilog.Events;
using System.Diagnostics.Metrics;

namespace Kadans.Api.Tests;

/// <summary>What the API reports about itself (ARCHITECTURE → Observability).</summary>
public class TelemetryTests
{
    private static HttpContext Answered(int status) => new DefaultHttpContext { Response = { StatusCode = status } };

    [Test]
    public async Task A_request_is_logged_when_it_went_wrong_or_was_slow_only()
    {
        await Assert.That(Telemetry.RequestLogLevel(Answered(200), 12, null)).IsEqualTo(LogEventLevel.Debug);
        await Assert.That(Telemetry.RequestLogLevel(Answered(204), 999, null)).IsEqualTo(LogEventLevel.Debug);
        await Assert.That(Telemetry.RequestLogLevel(Answered(200), 1500, null)).IsEqualTo(LogEventLevel.Warning);
        await Assert.That(Telemetry.RequestLogLevel(Answered(404), 3, null)).IsEqualTo(LogEventLevel.Information);
        await Assert.That(Telemetry.RequestLogLevel(Answered(503), 3, null)).IsEqualTo(LogEventLevel.Error);
        await Assert.That(Telemetry.RequestLogLevel(Answered(200), 3, new InvalidOperationException())).IsEqualTo(LogEventLevel.Error);
    }

    [Test]
    public async Task A_hub_connection_is_long_by_nature_not_slow()
    {
        var connection = new DefaultHttpContext { Request = { Path = "/hubs/kadans" }, Response = { StatusCode = 101 } };
        await Assert.That(Telemetry.RequestLogLevel(connection, 80_000, null)).IsEqualTo(LogEventLevel.Debug);

        var failed = new DefaultHttpContext { Request = { Path = "/hubs/kadans" }, Response = { StatusCode = 500 } };
        await Assert.That(Telemetry.RequestLogLevel(failed, 80_000, null)).IsEqualTo(LogEventLevel.Error);
    }

    private sealed class Collected(List<LogEvent> events) : ILogEventSink
    {
        public void Emit(LogEvent logEvent)
        {
            lock (events)
                events.Add(logEvent);
        }
    }

    /// <summary>A server logging as Program does (the request log, secret links hidden), with ASP.NET's own logs turned all the way up.</summary>
    private static async Task<(WebApplication App, List<LogEvent> Events)> StartLoggingAsync()
    {
        var events = new List<LogEvent>();
        var builder = WebApplication.CreateBuilder();
        builder.WebHost.UseTestServer();
        var serilog = new LoggerConfiguration().MinimumLevel.Verbose().HideSecretLinks().WriteTo.Sink(new Collected(events)).CreateLogger();
        builder.Services.AddSerilog(serilog, dispose: true);
        var app = builder.Build();
        app.UseSerilogRequestLogging(options =>
        {
            options.GetLevel = Telemetry.RequestLogLevel;
            // Program's logger is Serilog's static one, which the request log uses by default; tests run side by side.
            options.Logger = serilog;
        });
        app.MapGet(
            "/calendar/feed/{secret}/kadans.ics",
            (string secret, ILogger<TelemetryTests> logger) =>
            {
                // A line some code writes while the feed is read: ASP.NET attaches the request's path to it.
                logger.LogWarning("Reading a feed");
                return secret.EndsWith("-broken") ? throw new InvalidOperationException("the database is away") : Results.NotFound();
            }
        );
        app.MapGet("/todos/{id}", () => Results.NotFound());
        await app.StartAsync();
        return (app, events);
    }

    private static List<LogEvent> Snapshot(List<LogEvent> events)
    {
        lock (events)
            return [.. events];
    }

    /// <summary>The paths the request log wrote, one per request ("HTTP GET /path responded 404 in 3 ms").</summary>
    private static List<string> RequestLog(List<LogEvent> lines) =>
        [
            .. lines
                .Where(line => line.MessageTemplate.Text.StartsWith("HTTP {RequestMethod} {RequestPath}"))
                .Select(line => (line.Properties["RequestPath"] as ScalarValue)?.Value as string ?? "(no path)"),
        ];

    /// <summary>Everything a log event would put in Loki or on the console: its message and each of its properties.</summary>
    private static string Written(LogEvent line) =>
        line.RenderMessage() + " " + string.Join(" ", line.Properties.Select(p => $"{p.Key}={p.Value}")) + " " + line.Exception;

    [Test]
    public async Task A_calendar_feeds_link_is_in_no_log_line_whoever_wrote_it()
    {
        // The link lets whoever holds it read an account's todos. A found feed, a wrong one, an address under the
        // prefix that is no route, a query, a failure: the request log, the code's own lines and ASP.NET's.
        var (app, events) = await StartLoggingAsync();
        await using var _ = app;
        var client = app.GetTestClient();

        await client.GetAsync("/calendar/feed/THE-SECRET/kadans.ics");
        await client.GetAsync("/Calendar/Feed/THE-SECRET/kadans.ics?refresh=1");
        await client.GetAsync("/calendar/feed/THE-SECRET/not-the-file");
        await Assert.That(async () => await client.GetAsync("/calendar/feed/THE-SECRET-broken/kadans.ics")).Throws<InvalidOperationException>();

        var lines = Snapshot(events);
        // The request log's four lines, the handler's three, and ASP.NET's own about each request.
        await Assert.That(RequestLog(lines)).IsEquivalentTo(Enumerable.Repeat("/calendar/feed/…", 4));
        await Assert.That(lines.Count(line => line.RenderMessage() == "Reading a feed")).IsEqualTo(3);
        await Assert.That(lines.Count).IsGreaterThan(12);
        await Assert.That(lines.Where(line => Written(line).Contains("THE-SECRET", StringComparison.OrdinalIgnoreCase)).Select(Written)).IsEmpty();
        // Hidden, not dropped: the lines still say a feed was asked for.
        await Assert.That(lines.Count(line => Written(line).Contains("/calendar/feed/…", StringComparison.OrdinalIgnoreCase))).IsGreaterThan(12);
    }

    [Test]
    public async Task Every_other_path_is_logged_whole()
    {
        var (app, events) = await StartLoggingAsync();
        await using var _ = app;

        await app.GetTestClient().GetAsync("/todos/42");

        await Assert.That(RequestLog(Snapshot(events))).IsEquivalentTo(["/todos/42"]);
    }

    [Test]
    public async Task Every_job_pass_is_timed_with_its_outcome()
    {
        var meters = new ServiceCollection().AddMetrics().BuildServiceProvider().GetRequiredService<IMeterFactory>();
        using var collector = new MetricCollector<double>(meters, JobMetrics.MeterName, "kadans.job.duration");
        var jobs = new JobMetrics(meters);

        jobs.Record("occurrence-reminder", TimeSpan.FromMilliseconds(250), failed: false);
        jobs.Record("budget-recurring", TimeSpan.FromSeconds(2), failed: true);

        var passes = collector.GetMeasurementSnapshot();
        await Assert.That(passes.Count).IsEqualTo(2);
        await Assert.That(passes[0].Value).IsEqualTo(0.25);
        await Assert.That(passes[0].Tags["job.name"]).IsEqualTo("occurrence-reminder");
        await Assert.That(passes[1].Tags["outcome"]).IsEqualTo("error");
    }

    [Test]
    public async Task Kadans_meters_are_all_exported()
    {
        // One wildcard covers every module's meter; a new module's "Kadans.<Module>" needs no change here.
        await Assert.That(Telemetry.Meters).Contains("Kadans.*");
    }
}
