using System.Diagnostics;
using System.Diagnostics.Metrics;
using System.Globalization;
using System.Threading.RateLimiting;
using Kadans.SharedKernel.Errors;
using Microsoft.AspNetCore.Http.Features;

namespace Kadans.Api;

/// <summary>How much work the API takes on at once (ARCHITECTURE → Admission control). Production values live in appsettings.json.</summary>
internal sealed class AdmissionOptions
{
    public const string SectionName = "Admission";

    /// <summary>
    /// Requests worked on at once. Below the database pool (<c>KadansDatabase.MaxPoolSize</c>, 40) with room left for the
    /// scheduled jobs, so an admitted request never queues for a connection (and never times out there).
    /// </summary>
    public int MaxConcurrentRequests { get; set; } = 32;

    /// <summary>Requests that may wait for a place, first come first served; the next one is turned away at once.</summary>
    public int QueueLimit { get; set; } = 128;

    /// <summary>How long a request may wait for a place before it is turned away.</summary>
    public int QueueTimeoutMilliseconds { get; set; } = 2000;

    /// <summary>The Retry-After of a request turned away.</summary>
    public int RetryAfterSeconds { get; set; } = 5;
}

/// <summary>
/// Load shedding. Past what the server can work on at once, a request waits for a place in a short queue, and past the
/// queue it is answered straight away with a 503 and Retry-After. Without it an overloaded API takes on every request,
/// each one slows all the others, the database pool and the memory run out, and in the end nothing is answered (the
/// load test's run 4, docs/LOADTEST.md). It runs before authentication, so a request turned away has cost no token
/// check and no query. Health checks always pass. A hub connection lasts as long as the app stays open, so it takes no
/// place; a new one is turned away while requests are waiting for one.
/// </summary>
internal sealed class AdmissionControl : IDisposable
{
    private const string Busy = "The server is busy. Try again in a moment.";
    private static readonly TimeSpan LogEvery = TimeSpan.FromSeconds(10);

    private readonly ConcurrencyLimiter places;
    private readonly TimeSpan queueTimeout;
    private readonly string retryAfter;
    private readonly AdmissionMetrics metrics;
    private readonly ILogger logger;
    private long turnedAwaySinceLog;
    private long nextLogAt;

    public AdmissionControl(AdmissionOptions options, AdmissionMetrics metrics, ILogger<AdmissionControl> logger)
    {
        places = new ConcurrencyLimiter(
            new ConcurrencyLimiterOptions
            {
                PermitLimit = Math.Max(1, options.MaxConcurrentRequests),
                QueueLimit = Math.Max(0, options.QueueLimit),
                QueueProcessingOrder = QueueProcessingOrder.OldestFirst,
            }
        );
        queueTimeout = TimeSpan.FromMilliseconds(Math.Max(1, options.QueueTimeoutMilliseconds));
        retryAfter = Math.Max(1, options.RetryAfterSeconds).ToString(CultureInfo.InvariantCulture);
        this.metrics = metrics;
        this.logger = logger;
        metrics.ObserveWaiting(() => Waiting);
    }

    internal long Waiting => places.GetStatistics()?.CurrentQueuedCount ?? 0;

    public async Task InvokeAsync(HttpContext http, RequestDelegate next)
    {
        var path = http.Request.Path;
        if (path.StartsWithSegments("/health"))
        {
            await next(http);
            return;
        }
        if (path.StartsWithSegments("/hubs"))
        {
            if (Waiting > 0)
                await TurnAwayAsync(http, "hub");
            else
                await next(http);
            return;
        }

        var place = places.AttemptAcquire();
        if (!place.IsAcquired)
        {
            place.Dispose();
            var queuedAt = Stopwatch.GetTimestamp();
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(http.RequestAborted);
            timeout.CancelAfter(queueTimeout);
            try
            {
                place = await places.AcquireAsync(1, timeout.Token);
            }
            catch (OperationCanceledException) when (http.RequestAborted.IsCancellationRequested)
            {
                return; // the client left while waiting: nobody to answer
            }
            catch (OperationCanceledException)
            {
                await TurnAwayAsync(http, "queue_timeout");
                return;
            }

            if (!place.IsAcquired)
            {
                place.Dispose();
                await TurnAwayAsync(http, "queue_full");
                return;
            }
            metrics.Waited(Stopwatch.GetElapsedTime(queuedAt));
        }

        using (place)
            await next(http);
    }

    private async Task TurnAwayAsync(HttpContext http, string reason)
    {
        metrics.TurnedAway(reason);
        LogNowAndThen();
        // Marked in http.server.request.duration too: the "Server errors" panel and alert leave these 503s to their own.
        http.Features.Get<IHttpMetricsTagsFeature>()?.Tags.Add(new("kadans.admission", "shed"));
        http.Response.Headers.RetryAfter = retryAfter;
        var problem = new ApplicationError(ErrorTypes.ServerBusy, Busy).ToProblemDetails(http);
        await TypedResults.Problem(problem).ExecuteAsync(http);
    }

    /// <summary>
    /// A warning on the first request turned away, then at most one every ten seconds while it lasts: a line per
    /// request would add to the load it reports. The metric counts every one.
    /// </summary>
    private void LogNowAndThen()
    {
        Interlocked.Increment(ref turnedAwaySinceLog);
        var now = Environment.TickCount64;
        var due = Volatile.Read(ref nextLogAt);
        if (now < due || Interlocked.CompareExchange(ref nextLogAt, now + (long)LogEvery.TotalMilliseconds, due) != due)
            return;
        logger.LogWarning("Server busy: {Count} request(s) turned away since the previous warning", Interlocked.Exchange(ref turnedAwaySinceLog, 0));
    }

    public void Dispose() => places.Dispose();
}

/// <summary>Admission control as Prometheus sees it (ARCHITECTURE → Observability).</summary>
internal sealed class AdmissionMetrics
{
    public const string MeterName = "Kadans.Api";

    private readonly Meter meter;
    private readonly Counter<long> shed;
    private readonly Histogram<double> wait;

    public AdmissionMetrics(IMeterFactory meters)
    {
        meter = meters.Create(MeterName);
        shed = meter.CreateCounter<long>(
            "kadans.admission.shed",
            "{request}",
            "Requests turned away with a 503, by reason: queue_full, queue_timeout, hub (a new hub connection while requests wait)"
        );
        wait = meter.CreateHistogram<double>(
            "kadans.admission.wait",
            "s",
            "How long a request waited for a place before it was worked on (only those that waited)",
            advice: new InstrumentAdvice<double> { HistogramBucketBoundaries = [0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2, 5] }
        );
    }

    /// <summary>Requests waiting for a place, read at each export.</summary>
    public void ObserveWaiting(Func<long> waiting) =>
        meter.CreateObservableGauge("kadans.admission.waiting", waiting, "{request}", "Requests waiting for a place");

    public void TurnedAway(string reason) => shed.Add(1, new KeyValuePair<string, object?>("reason", reason));

    public void Waited(TimeSpan took) => wait.Record(took.TotalSeconds);
}

internal static class Admission
{
    public static IServiceCollection AddKadansAdmissionControl(this IServiceCollection services, IConfiguration configuration)
    {
        var options = configuration.GetSection(AdmissionOptions.SectionName).Get<AdmissionOptions>() ?? new AdmissionOptions();
        return services.AddSingleton(options).AddSingleton<AdmissionMetrics>().AddSingleton<AdmissionControl>();
    }

    public static IApplicationBuilder UseKadansAdmissionControl(this IApplicationBuilder app) =>
        app.Use(app.ApplicationServices.GetRequiredService<AdmissionControl>().InvokeAsync);
}
