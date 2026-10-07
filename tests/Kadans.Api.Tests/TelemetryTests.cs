using Kadans.Api;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;
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
