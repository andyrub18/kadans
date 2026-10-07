using System.Diagnostics.Metrics;

namespace Kadans.Modules.Notifications.Push;

/// <summary>
/// Push, as Prometheus sees it (ARCHITECTURE → Observability): the queue's length and what it drops, how long a push
/// waits before the provider has it, and what the provider answers per device.
/// </summary>
internal sealed class PushMetrics
{
    public const string MeterName = "Kadans.Notifications";

    private readonly Meter meter;
    private readonly Counter<long> dropped;
    private readonly Counter<long> withheld;
    private readonly Counter<long> messages;
    private readonly Histogram<double> delay;

    public PushMetrics(IMeterFactory meters)
    {
        meter = meters.Create(MeterName);
        dropped = meter.CreateCounter<long>("kadans.push.dropped", "{push}", "Pushes the full queue dropped (the oldest goes first)");
        withheld = meter.CreateCounter<long>("kadans.push.withheld", "{device}", "Phones left out of a push: the account has no subscription");
        messages = meter.CreateCounter<long>("kadans.push.messages", "{message}", "Messages handed to the push provider, one per device, by result (sent, failed, dead)");
        delay = meter.CreateHistogram<double>(
            "kadans.push.delay",
            "s",
            "From queued to answered by the provider",
            advice: new InstrumentAdvice<double> { HistogramBucketBoundaries = [0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 30, 60, 120, 300] }
        );
    }

    /// <summary>The queue's length, read at each export.</summary>
    public void ObserveQueue(Func<int> length) =>
        meter.CreateObservableGauge("kadans.push.queue.length", () => length(), "{push}", "Pushes waiting in the queue");

    public void Dropped() => dropped.Add(1);

    public void Withheld(int phones) => withheld.Add(phones);

    public void Delivered(TimeSpan sinceQueued) => delay.Record(sinceQueued.TotalSeconds);

    public void Answered(int sent, int failed, int dead)
    {
        if (sent > 0)
            messages.Add(sent, new KeyValuePair<string, object?>("result", "sent"));
        if (failed > 0)
            messages.Add(failed, new KeyValuePair<string, object?>("result", "failed"));
        if (dead > 0)
            messages.Add(dead, new KeyValuePair<string, object?>("result", "dead"));
    }
}
