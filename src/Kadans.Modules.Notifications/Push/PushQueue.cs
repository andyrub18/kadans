using System.Threading.Channels;
using Kadans.SharedKernel.Notifications;
using Kadans.SharedKernel.Users;

namespace Kadans.Modules.Notifications.Push;

internal sealed record PushRequest(string UserId, NotificationMessage Message);

/// <summary>
/// Push leaves the caller's path here. A call to FCM takes from a few hundred milliseconds to
/// seconds; the request that advanced a pomodoro phase (or the pass announcing it) must not wait
/// for it – the notification is already stored and on the hub by the time it is queued.
/// In-memory on purpose: a push lost to a restart is a missed banner, never missed data.
/// </summary>
internal sealed class PushQueue
{
    private readonly Channel<PushRequest> channel = Channel.CreateBounded<PushRequest>(
        new BoundedChannelOptions(1000) { FullMode = BoundedChannelFullMode.DropOldest, SingleReader = true }
    );

    public void Enqueue(PushRequest request) => channel.Writer.TryWrite(request);

    public IAsyncEnumerable<PushRequest> ReadAllAsync(CancellationToken cancellationToken) =>
        channel.Reader.ReadAllAsync(cancellationToken);
}

internal sealed class PushWorker(PushQueue queue, IServiceScopeFactory scopes, IPushSender push, ILogger<PushWorker> logger)
    : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        try
        {
            await foreach (var request in queue.ReadAllAsync(stoppingToken))
            {
                // IDevicePushTargets is scoped (it reads the Identity database).
                await using var scope = scopes.CreateAsyncScope();
                await DeliverAsync(
                    request,
                    scope.ServiceProvider.GetRequiredService<IDevicePushTargets>(),
                    scope.ServiceProvider.GetRequiredService<IMobileAccess>(),
                    push,
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
    /// Send to the user's devices and retire the tokens the provider reports dead. Never throws. Phones only for an
    /// account with a subscription (<see cref="IMobileAccess"/>): reminders on a phone are what it pays for.
    /// </summary>
    internal static async Task DeliverAsync(
        PushRequest request,
        IDevicePushTargets devices,
        IMobileAccess mobileAccess,
        IPushSender push,
        ILogger logger,
        CancellationToken cancellationToken
    )
    {
        try
        {
            var targets = await devices.ForUserAsync(request.UserId, cancellationToken);
            if (targets.Any(IsPhone) && !await mobileAccess.AllowsPhonesAsync(request.UserId, cancellationToken))
                targets = [.. targets.Where(t => !IsPhone(t))];
            if (targets.Count == 0)
                return;

            var dead = await push.SendAsync(targets, request.Message, cancellationToken);
            foreach (var token in dead)
                await devices.InvalidateAsync(token, cancellationToken);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            logger.LogError(ex, "Push failed for user {UserId}", request.UserId);
        }
    }

    private static bool IsPhone(PushTarget target) => target.Platform is "Android" or "Ios";
}
