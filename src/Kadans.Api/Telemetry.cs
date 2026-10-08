using System.Diagnostics.Metrics;
using System.Reflection;
using OpenTelemetry.Exporter;
using OpenTelemetry.Metrics;
using OpenTelemetry.Resources;
using Quartz;
using Serilog;
using Serilog.Events;
using Serilog.Sinks.OpenTelemetry;

namespace Kadans.Api;

/// <summary>
/// Where metrics and logs go (ARCHITECTURE → Observability): metrics to Prometheus and logs to Loki, both pushed over
/// OTLP. Either one is off while its endpoint is empty; nothing leaves the process then.
/// </summary>
internal sealed class TelemetryOptions
{
    public const string SectionName = "Telemetry";

    /// <summary>The <c>job</c> label in Prometheus, <c>service_name</c> in Loki.</summary>
    public string ServiceName { get; set; } = "kadans-api";

    /// <summary>One instance by design, so a fixed name: a new one per restart would start new series each time.</summary>
    public string InstanceId { get; set; } = "api";

    /// <summary>Prometheus's OTLP receiver, e.g. <c>http://prometheus:9090/api/v1/otlp/v1/metrics</c>.</summary>
    public string? MetricsEndpoint { get; set; }

    /// <summary>Loki's OTLP endpoint, e.g. <c>http://loki:3100/otlp/v1/logs</c>.</summary>
    public string? LogsEndpoint { get; set; }

    public int MetricsIntervalSeconds { get; set; } = 15;
}

internal static class Telemetry
{
    /// <summary>
    /// ASP.NET Core's and .NET's own meters (requests, Kestrel, SignalR, rate limiting, sign-ins, HttpClient, runtime),
    /// the database's, and Kadans' own: one per module, all named <c>Kadans.*</c>.
    /// </summary>
    internal static readonly string[] Meters =
    [
        "Microsoft.AspNetCore.Hosting",
        "Microsoft.AspNetCore.Server.Kestrel",
        "Microsoft.AspNetCore.Http.Connections",
        "Microsoft.AspNetCore.Diagnostics",
        "Microsoft.AspNetCore.RateLimiting",
        "Microsoft.AspNetCore.Authentication",
        "Microsoft.AspNetCore.Authorization",
        "Microsoft.AspNetCore.Identity",
        "System.Net.Http",
        "System.Runtime",
        "Npgsql",
        "Microsoft.EntityFrameworkCore",
        "Kadans.*",
    ];

    private static string Version =>
        typeof(Telemetry).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion ?? "unknown";

    public static TelemetryOptions Options(IConfiguration configuration) =>
        configuration.GetSection(TelemetryOptions.SectionName).Get<TelemetryOptions>() ?? new TelemetryOptions();

    /// <summary>Metrics, exported every <see cref="TelemetryOptions.MetricsIntervalSeconds"/> to Prometheus.</summary>
    public static void AddKadansMetrics(this WebApplicationBuilder builder)
    {
        var options = Options(builder.Configuration);
        builder.Services.AddQuartz(quartz => quartz.AddJobListener<JobMetrics>());
        if (string.IsNullOrWhiteSpace(options.MetricsEndpoint))
            return;

        builder
            .Services.AddOpenTelemetry()
            .ConfigureResource(resource => resource
                .AddService(options.ServiceName, serviceVersion: Version, autoGenerateServiceInstanceId: false, serviceInstanceId: options.InstanceId)
                .AddAttributes([new("deployment.environment.name", builder.Environment.EnvironmentName.ToLowerInvariant())]))
            .WithMetrics(metrics => metrics
                .AddMeter(Meters)
                .AddOtlpExporter((exporter, reader) =>
                {
                    exporter.Endpoint = new Uri(options.MetricsEndpoint);
                    exporter.Protocol = OtlpExportProtocol.HttpProtobuf;
                    reader.PeriodicExportingMetricReaderOptions.ExportIntervalMilliseconds = options.MetricsIntervalSeconds * 1000;
                }));
    }

    /// <summary>
    /// Logs to Loki as well as the console. Each event's properties arrive as structured metadata, nested ones
    /// flattened with <c>_</c>: <c>{service_name="kadans-api"} | UserId="…"</c>, no parsing needed.
    /// </summary>
    public static LoggerConfiguration WriteToLoki(this LoggerConfiguration logger, TelemetryOptions options, string environment)
    {
        if (string.IsNullOrWhiteSpace(options.LogsEndpoint))
            return logger;

        return logger.WriteTo.OpenTelemetry(sink =>
        {
            sink.LogsEndpoint = options.LogsEndpoint;
            sink.Protocol = OtlpProtocol.HttpProtobuf;
            sink.ResourceAttributes = new Dictionary<string, object>
            {
                ["service.name"] = options.ServiceName,
                ["service.instance.id"] = options.InstanceId,
                ["service.version"] = Version,
                ["deployment.environment.name"] = environment.ToLowerInvariant(),
            };
        });
    }

    /// <summary>
    /// One line per request is the metrics' job now: a request is logged when it went wrong (4xx, 5xx, an exception) or
    /// was slow (over a second). The rest is Debug, below the configured minimum, so health checks and the steady
    /// stream of successful calls stay out of the logs. The hub's connections last as long as an app stays open: long
    /// is normal there, not slow.
    /// </summary>
    internal static LogEventLevel RequestLogLevel(HttpContext context, double elapsedMilliseconds, Exception? exception) =>
        exception is not null || context.Response.StatusCode >= 500 ? LogEventLevel.Error
        : elapsedMilliseconds > 1000 && !context.Request.Path.StartsWithSegments("/hubs") ? LogEventLevel.Warning
        : context.Response.StatusCode >= 400 ? LogEventLevel.Information
        : LogEventLevel.Debug;
}

/// <summary>How long every scheduled job's pass takes, and whether it failed: <c>kadans.job.duration</c> by <c>job.name</c>.</summary>
internal sealed class JobMetrics : IJobListener
{
    public const string MeterName = "Kadans.Jobs";

    private readonly Histogram<double> duration;

    public JobMetrics(IMeterFactory meters)
    {
        duration = meters
            .Create(MeterName)
            .CreateHistogram<double>(
                "kadans.job.duration",
                "s",
                "One pass of a scheduled job, by job.name and outcome (ok, error)",
                advice: new InstrumentAdvice<double> { HistogramBucketBoundaries = [0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60, 120, 300] }
            );
    }

    public string Name => "kadans-job-metrics";

    public Task JobToBeExecuted(IJobExecutionContext context, CancellationToken cancellationToken = default) => Task.CompletedTask;

    public Task JobExecutionVetoed(IJobExecutionContext context, CancellationToken cancellationToken = default) => Task.CompletedTask;

    public Task JobWasExecuted(IJobExecutionContext context, JobExecutionException? jobException, CancellationToken cancellationToken = default)
    {
        Record(context.JobDetail.Key.Name, context.JobRunTime, failed: jobException is not null);
        return Task.CompletedTask;
    }

    internal void Record(string job, TimeSpan took, bool failed) =>
        duration.Record(
            took.TotalSeconds,
            new KeyValuePair<string, object?>("job.name", job),
            new KeyValuePair<string, object?>("outcome", failed ? "error" : "ok")
        );
}
