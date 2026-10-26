using System.Collections.Concurrent;
using System.Text.Json;
using Kadans.Modules.Notifications.Realtime;
using Kadans.SharedKernel.Realtime;
using Microsoft.Extensions.Logging.Abstractions;

namespace Kadans.Notifications.Tests;

/// <summary>
/// A burst of saves (a todo and its occurrences, a movement and the limit alert it set off) reaches each account's open
/// apps as one "data.changed" naming every area that changed.
/// </summary>
public class LiveChangesTests
{
    private sealed class Hub : IRealtimePublisher
    {
        public ConcurrentQueue<(string UserId, string Event, string Payload)> Sent { get; } = new();

        public Task PublishToUserAsync(string userId, string eventName, object payload, CancellationToken cancellationToken = default)
        {
            Sent.Enqueue((userId, eventName, JsonSerializer.Serialize(payload)));
            return Task.CompletedTask;
        }
    }

    [Test]
    public async Task Each_account_hears_once_per_burst_with_every_area_it_changed_in()
    {
        var changes = new LiveChanges();
        var hub = new Hub();
        using var publisher = new LiveChangesPublisher(changes, hub, NullLogger<LiveChangesPublisher>.Instance);
        await publisher.StartAsync(CancellationToken.None);

        changes.Changed("alice", LiveAreas.Todos);
        changes.Changed("alice", LiveAreas.Todos);
        changes.Changed("bob", LiveAreas.Budget);
        changes.Changed("alice", LiveAreas.Budget);

        var waited = TimeSpan.Zero;
        while (hub.Sent.Count < 2 && waited < TimeSpan.FromSeconds(10))
        {
            await Task.Delay(50);
            waited += TimeSpan.FromMilliseconds(50);
        }
        await Task.Delay(LiveChangesPublisher.Gather * 2); // nothing more comes for the same burst
        await publisher.StopAsync(CancellationToken.None);

        var sent = hub.Sent.OrderBy(s => s.UserId, StringComparer.Ordinal).ToList();
        await Assert.That(sent.Count).IsEqualTo(2);
        await Assert.That(sent.All(s => s.Event == LiveAreas.Event)).IsTrue();
        await Assert.That(sent[0]).IsEqualTo(("alice", "data.changed", """{"areas":["budget","todos"]}"""));
        await Assert.That(sent[1]).IsEqualTo(("bob", "data.changed", """{"areas":["budget"]}"""));
    }

    [Test]
    public async Task Changes_are_gathered_per_account_and_area()
    {
        var gathered = LiveChanges.PerAccount([("alice", "todos"), ("bob", "todos"), ("alice", "todos"), ("alice", "budget")]);

        await Assert.That(gathered.Count).IsEqualTo(2);
        await Assert.That(gathered["alice"].ToList()).IsEquivalentTo(["budget", "todos"]);
        await Assert.That(gathered["bob"].ToList()).IsEquivalentTo(["todos"]);
    }
}
