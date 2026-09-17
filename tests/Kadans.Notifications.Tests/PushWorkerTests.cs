using Kadans.Modules.Notifications.Push;
using Kadans.SharedKernel.Notifications;
using Kadans.SharedKernel.Users;
using Microsoft.Extensions.Logging.Abstractions;

namespace Kadans.Notifications.Tests;

public class PushWorkerTests
{
    private sealed class FakeDevices(params PushTarget[] targets) : IDevicePushTargets
    {
        public List<string> Invalidated { get; } = [];

        public Task<IReadOnlyList<PushTarget>> ForUserAsync(string userId, CancellationToken cancellationToken = default) =>
            Task.FromResult<IReadOnlyList<PushTarget>>(targets);

        public Task InvalidateAsync(string pushToken, CancellationToken cancellationToken = default)
        {
            Invalidated.Add(pushToken);
            return Task.CompletedTask;
        }
    }

    private sealed class FakeSender(Func<IReadOnlyList<PushTarget>, IReadOnlyList<string>> send) : IPushSender
    {
        public int Calls { get; private set; }

        public Task<IReadOnlyList<string>> SendAsync(IReadOnlyList<PushTarget> targets, NotificationMessage message, CancellationToken cancellationToken = default)
        {
            Calls++;
            return Task.FromResult(send(targets));
        }
    }

    private static readonly PushRequest Request = new("user-1", new NotificationMessage("pomodoro.phase.completed", "Deep work", "Break — 5 min", null));

    [Test]
    public async Task Sends_to_the_users_devices_and_retires_dead_tokens()
    {
        var devices = new FakeDevices(new PushTarget("Android", "live"), new PushTarget("Android", "dead"));
        var sender = new FakeSender(_ => ["dead"]);

        await PushWorker.DeliverAsync(Request, devices, sender, NullLogger.Instance, CancellationToken.None);

        await Assert.That(sender.Calls).IsEqualTo(1);
        await Assert.That(devices.Invalidated).IsEquivalentTo(["dead"]);
    }

    [Test]
    public async Task No_registered_device_means_the_provider_is_never_called()
    {
        var sender = new FakeSender(_ => []);

        await PushWorker.DeliverAsync(Request, new FakeDevices(), sender, NullLogger.Instance, CancellationToken.None);

        await Assert.That(sender.Calls).IsEqualTo(0);
    }

    [Test]
    public async Task A_failing_provider_never_takes_the_worker_down()
    {
        var sender = new FakeSender(_ => throw new InvalidOperationException("FCM is down"));

        await PushWorker.DeliverAsync(Request, new FakeDevices(new PushTarget("Android", "live")), sender, NullLogger.Instance, CancellationToken.None);

        await Assert.That(sender.Calls).IsEqualTo(1); // reached here: nothing propagated
    }

    [Test]
    public async Task The_queue_hands_requests_over_in_order()
    {
        var queue = new PushQueue();
        queue.Enqueue(Request with { UserId = "a" });
        queue.Enqueue(Request with { UserId = "b" });

        var seen = new List<string>();
        using var stop = new CancellationTokenSource();
        try
        {
            await foreach (var request in queue.ReadAllAsync(stop.Token))
            {
                seen.Add(request.UserId);
                if (seen.Count == 2)
                    stop.Cancel();
            }
        }
        catch (OperationCanceledException) { }

        await Assert.That(seen).IsEquivalentTo(["a", "b"]);
    }
}
