using System.Diagnostics;
using System.Threading.Channels;
using Kadans.SharedKernel.Notifications;
using Kadans.SharedKernel.Users;

namespace Kadans.Modules.Notifications.Push;

/// <param name="Silent">A signal for the apps (<see cref="INotificationDispatcher.SignalAsync"/>), not a notification: only
/// phones that ring their own reminders understand it.</param>
internal sealed record PushRequest(string UserId, NotificationMessage Message, bool Silent = false)
{
    /// <summary>When it was queued (<see cref="Stopwatch"/> ticks): how long a push waits is a measured number.</summary>
    public long QueuedAt { get; init; } = Stopwatch.GetTimestamp();
}

/// <summary>
/// Push leaves the caller's path here. A call to FCM takes from a few hundred milliseconds to
/// seconds; the request that advanced a pomodoro phase (or the pass announcing it) must not wait
/// for it – the notification is already stored and on the hub by the time it is queued.
/// In-memory on purpose: a push lost to a restart is a missed banner, never missed data. Room for a
/// reminder peak (everyone's 08:00): 50,000 waiting at most, a few kilobytes each.
/// </summary>
internal sealed class PushQueue
{
    internal const int Capacity = 50_000;

    private readonly Channel<PushRequest> channel;

    public PushQueue(PushMetrics metrics, int capacity = Capacity)
    {
        // Full: the oldest goes, and the drop is counted (an alert watches it).
        channel = Channel.CreateBounded<PushRequest>(
            new BoundedChannelOptions(capacity) { FullMode = BoundedChannelFullMode.DropOldest },
            _ => metrics.Dropped()
        );
        metrics.ObserveQueue(() => channel.Reader.Count);
    }

    public void Enqueue(PushRequest request) => channel.Writer.TryWrite(request);

    public IAsyncEnumerable<PushRequest> ReadAllAsync(CancellationToken cancellationToken) =>
        channel.Reader.ReadAllAsync(cancellationToken);

    /// <summary>Waits for one request, then takes whatever else is already waiting, up to <paramref name="max"/>.</summary>
    public async Task<List<PushRequest>> ReadBatchAsync(int max, CancellationToken cancellationToken)
    {
        var batch = new List<PushRequest>();
        if (!await channel.Reader.WaitToReadAsync(cancellationToken))
            return batch;
        while (batch.Count < max && channel.Reader.TryRead(out var request))
            batch.Add(request);
        return batch;
    }
}

/// <summary>
/// Sends what the queue holds, a batch at a time: up to 500 requests, their devices looked up in one query, their
/// messages handed to the provider 500 per call (Firebase's SendEach). <see cref="Workers"/> of them work side by
/// side, so a slow call does not hold the rest back. One user at a time, a peak of 20,000 reminders took the better
/// part of an hour to push and overflowed the queue (docs/LOADTEST.md).
/// </summary>
internal sealed class PushWorker(PushQueue queue, IServiceScopeFactory scopes, IPushSender push, PushMetrics metrics, ILogger<PushWorker> logger)
    : BackgroundService
{
    internal const int Workers = 4;
    internal const int RequestsPerBatch = 500;

    protected override Task ExecuteAsync(CancellationToken stoppingToken) =>
        Task.WhenAll(Enumerable.Range(0, Workers).Select(_ => WorkAsync(stoppingToken)));

    private async Task WorkAsync(CancellationToken stoppingToken)
    {
        try
        {
            while (!stoppingToken.IsCancellationRequested)
            {
                var batch = await queue.ReadBatchAsync(RequestsPerBatch, stoppingToken);
                if (batch.Count == 0)
                    return; // the queue was completed

                // IDevicePushTargets is scoped (it reads the Identity database).
                await using var scope = scopes.CreateAsyncScope();
                await DeliverAsync(
                    batch,
                    scope.ServiceProvider.GetRequiredService<IDevicePushTargets>(),
                    scope.ServiceProvider.GetRequiredService<IMobileAccess>(),
                    push,
                    metrics,
                    logger,
                    stoppingToken
                );
            }
        }
        catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
        {
            // shutting down
        }
    }

    /// <summary>
    /// How long a device's reminder window counts after its sync. A phone syncs twice a day; one that has not for this
    /// long may have lost its alarms (the app removed, killed by the system), so the push takes over again.
    /// </summary>
    internal static readonly TimeSpan WindowLifetime = TimeSpan.FromHours(36);

    /// <summary>
    /// Send to the users' devices and retire the tokens the provider reports dead. Never throws. Phones only for an
    /// account with a subscription (<see cref="IMobileAccess"/>): reminders on a phone are what it pays for. A reminder
    /// skips the devices that ring it themselves (<see cref="Covers"/>); a silent signal goes only to those.
    /// </summary>
    internal static async Task DeliverAsync(
        IReadOnlyList<PushRequest> batch,
        IDevicePushTargets devices,
        IMobileAccess mobileAccess,
        IPushSender push,
        PushMetrics metrics,
        ILogger logger,
        CancellationToken cancellationToken
    )
    {
        try
        {
            var targetsByUser = await devices.ForUsersAsync([.. batch.Select(r => r.UserId).Distinct()], cancellationToken);
            var envelopes = new List<PushEnvelope>();
            foreach (var request in batch)
            {
                if (!targetsByUser.TryGetValue(request.UserId, out var targets) || targets.Count == 0)
                    continue;
                var phones = targets.Count(IsPhone);
                if (phones > 0 && !await mobileAccess.AllowsPhonesAsync(request.UserId, cancellationToken))
                {
                    targets = [.. targets.Where(t => !IsPhone(t))];
                    metrics.Withheld(phones);
                }
                if (request.Silent)
                {
                    targets = [.. targets.Where(t => t.RemindersSyncedAt is not null)];
                }
                else if (request.Message.Reminder is { } reminder)
                {
                    var onDevice = targets.Count(t => Covers(t, reminder));
                    if (onDevice > 0)
                    {
                        targets = [.. targets.Where(t => !Covers(t, reminder))];
                        metrics.SkippedOnDevice(onDevice);
                    }
                }
                envelopes.AddRange(targets.Select(t => new PushEnvelope(t, request.Message, request.Silent)));
            }

            foreach (var call in envelopes.Chunk(IPushSender.MaxPerCall))
            {
                try
                {
                    var outcome = await push.SendAsync(call, cancellationToken);
                    metrics.Answered(outcome.Sent, outcome.Failed, outcome.Dead.Count);
                    foreach (var token in outcome.Dead)
                        await devices.InvalidateAsync(token, cancellationToken);
                }
                catch (Exception ex) when (ex is not OperationCanceledException)
                {
                    logger.LogError(ex, "Push failed for {Count} message(s)", call.Length);
                }
            }

            foreach (var request in batch.Where(r => targetsByUser.ContainsKey(r.UserId)))
                metrics.Delivered(Stopwatch.GetElapsedTime(request.QueuedAt));
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            logger.LogError(ex, "Push failed for a batch of {Count} request(s)", batch.Count);
        }
    }

    private static bool IsPhone(PushTarget target) => target.Platform is "Android" or "Ios";

    /// <summary>
    /// The device rings this reminder itself: its window holds the account's latest reminders version, was fetched not
    /// too long ago, and reaches the notify time (ARCHITECTURE → "Reminders ring on the phone").
    /// </summary>
    internal static bool Covers(PushTarget target, ReminderDelivery reminder) =>
        target.RemindersSyncedAt is { } synced
        && target.RemindersVersion >= reminder.AccountVersion
        && reminder.NotifyAt <= Min(target.RemindersThrough ?? synced, synced + WindowLifetime);

    private static DateTimeOffset Min(DateTimeOffset a, DateTimeOffset b) => a < b ? a : b;
}
