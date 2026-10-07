using System.Diagnostics.Metrics;
using Kadans.Modules.Notifications.Push;
using Kadans.SharedKernel.Notifications;
using Kadans.SharedKernel.Users;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Diagnostics.Metrics.Testing;
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

    /// <summary>Answers with the dead tokens <paramref name="send"/> names; every other device was sent to.</summary>
    private sealed class FakeSender(Func<IReadOnlyList<PushTarget>, IReadOnlyList<string>> send) : IPushSender
    {
        public int Calls { get; private set; }

        public Task<PushOutcome> SendAsync(IReadOnlyList<PushTarget> targets, NotificationMessage message, CancellationToken cancellationToken = default)
        {
            Calls++;
            var dead = send(targets);
            return Task.FromResult(new PushOutcome(targets.Count - dead.Count, 0, dead));
        }
    }

    private readonly IMeterFactory meters = new ServiceCollection().AddMetrics().BuildServiceProvider().GetRequiredService<IMeterFactory>();
    private readonly PushMetrics metrics;

    public PushWorkerTests() => metrics = new PushMetrics(meters);

    private Task Deliver(PushRequest request, IDevicePushTargets devices, IMobileAccess phones, IPushSender sender) =>
        PushWorker.DeliverAsync(request, devices, phones, sender, metrics, NullLogger.Instance, CancellationToken.None);

    private MetricCollector<T> Collect<T>(string instrument) where T : struct => new(meters, PushMetrics.MeterName, instrument);

    /// <summary>The account's phone access, as Billing would answer it.</summary>
    private sealed class Phones(bool allowed) : IMobileAccess
    {
        public static readonly Phones Allowed = new(true);
        public static readonly Phones NotSubscribed = new(false);

        public Task<bool> AllowsPhonesAsync(string userId, CancellationToken cancellationToken = default) => Task.FromResult(allowed);
    }

    private static readonly PushRequest Request = new("user-1", new NotificationMessage("pomodoro.phase.completed", "Deep work", "Break — 5 min", null));

    [Test]
    public async Task Sends_to_the_users_devices_and_retires_dead_tokens()
    {
        var devices = new FakeDevices(new PushTarget("Android", "live"), new PushTarget("Android", "dead"));
        var sender = new FakeSender(_ => ["dead"]);

        await Deliver(Request, devices, Phones.Allowed, sender);

        await Assert.That(sender.Calls).IsEqualTo(1);
        await Assert.That(devices.Invalidated).IsEquivalentTo(["dead"]);
    }

    [Test]
    public async Task No_registered_device_means_the_provider_is_never_called()
    {
        var sender = new FakeSender(_ => []);

        await Deliver(Request, new FakeDevices(), Phones.Allowed, sender);

        await Assert.That(sender.Calls).IsEqualTo(0);
    }

    [Test]
    public async Task A_failing_provider_never_takes_the_worker_down()
    {
        var sender = new FakeSender(_ => throw new InvalidOperationException("FCM is down"));

        await Deliver(Request, new FakeDevices(new PushTarget("Android", "live")), Phones.Allowed, sender);

        await Assert.That(sender.Calls).IsEqualTo(1); // reached here: nothing propagated
    }

    [Test]
    public async Task The_queue_hands_requests_over_in_order()
    {
        var queue = new PushQueue(metrics);
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

    [Test]
    public async Task Phones_get_no_push_without_a_subscription_other_devices_still_do()
    {
        var sent = new List<string>();
        var sender = new FakeSender(targets =>
        {
            sent.AddRange(targets.Select(t => t.Platform));
            return [];
        });
        var devices = new FakeDevices(new PushTarget("Android", "phone"), new PushTarget("Ios", "iphone"), new PushTarget("Web", "browser"));

        await Deliver(Request, devices, Phones.NotSubscribed, sender);
        await Assert.That(sent).IsEquivalentTo(["Web"]);

        sent.Clear();
        await Deliver(Request, new FakeDevices(new PushTarget("Android", "phone")), Phones.NotSubscribed, sender);
        await Assert.That(sender.Calls).IsEqualTo(1); // the call above; nothing left to send to here
    }

    [Test]
    public async Task What_the_provider_answers_is_counted_per_device_and_how_long_it_waited()
    {
        using var messages = Collect<long>("kadans.push.messages");
        using var delay = Collect<double>("kadans.push.delay");
        var devices = new FakeDevices(new PushTarget("Android", "a"), new PushTarget("Android", "b"), new PushTarget("Ios", "dead"));

        await Deliver(Request, devices, Phones.Allowed, new FakeSender(_ => ["dead"]));

        var byResult = messages.GetMeasurementSnapshot().ToDictionary(m => (string)m.Tags["result"]!, m => m.Value);
        await Assert.That(byResult["sent"]).IsEqualTo(2);
        await Assert.That(byResult["dead"]).IsEqualTo(1);
        await Assert.That(byResult.ContainsKey("failed")).IsFalse();
        await Assert.That(delay.GetMeasurementSnapshot().Count).IsEqualTo(1);
    }

    [Test]
    public async Task Phones_left_out_for_want_of_a_subscription_are_counted()
    {
        using var withheld = Collect<long>("kadans.push.withheld");
        var devices = new FakeDevices(new PushTarget("Android", "phone"), new PushTarget("Ios", "iphone"), new PushTarget("Web", "browser"));

        await Deliver(Request, devices, Phones.NotSubscribed, new FakeSender(_ => []));
        await Deliver(Request, devices, Phones.Allowed, new FakeSender(_ => []));

        await Assert.That(withheld.GetMeasurementSnapshot().Sum(m => m.Value)).IsEqualTo(2);
    }

    [Test]
    public async Task A_full_queue_drops_the_oldest_and_says_so()
    {
        using var dropped = Collect<long>("kadans.push.dropped");
        using var length = Collect<int>("kadans.push.queue.length");
        var queue = new PushQueue(metrics);

        for (var i = 0; i < 1003; i++)
            queue.Enqueue(Request with { UserId = $"user-{i}" });

        await Assert.That(dropped.GetMeasurementSnapshot().Sum(m => m.Value)).IsEqualTo(3);
        length.RecordObservableInstruments();
        await Assert.That(length.LastMeasurement!.Value).IsEqualTo(1000);
    }

    [Test]
    public async Task The_simulated_provider_answers_like_firebase_500_messages_a_call()
    {
        var options = Microsoft.Extensions.Options.Options.Create(new PushOptions { Simulated = { LatencyMilliseconds = 40 } });
        var sender = new SimulatedPushSender(options);
        var targets = Enumerable.Range(0, 1001).Select(i => new PushTarget("Android", $"t{i}")).ToList();

        var watch = System.Diagnostics.Stopwatch.StartNew();
        var outcome = await sender.SendAsync(targets, Request.Message);

        await Assert.That(outcome.Sent).IsEqualTo(1001);
        await Assert.That(watch.ElapsedMilliseconds).IsGreaterThanOrEqualTo(110); // three calls of 40 ms
    }
}
