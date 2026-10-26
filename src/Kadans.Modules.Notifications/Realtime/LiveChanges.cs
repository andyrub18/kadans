using System.Threading.Channels;
using Kadans.SharedKernel.Realtime;

namespace Kadans.Modules.Notifications.Realtime;

/// <summary>The accounts whose data just changed, and where (<see cref="ILiveChanges"/>), until the publisher takes them.</summary>
internal sealed class LiveChanges : ILiveChanges
{
    private readonly Channel<(string UserId, string Area)> changed =
        Channel.CreateUnbounded<(string, string)>(new UnboundedChannelOptions { SingleReader = true });

    public void Changed(string userId, string area) => changed.Writer.TryWrite((userId, area));

    internal ChannelReader<(string UserId, string Area)> Changes => changed.Reader;

    /// <summary>Each account once, with every area it changed in.</summary>
    internal static Dictionary<string, SortedSet<string>> PerAccount(IEnumerable<(string UserId, string Area)> changes)
    {
        var accounts = new Dictionary<string, SortedSet<string>>(StringComparer.Ordinal);
        foreach (var (userId, area) in changes)
        {
            if (!accounts.TryGetValue(userId, out var areas))
                accounts[userId] = areas = new SortedSet<string>(StringComparer.Ordinal);
            areas.Add(area);
        }
        return accounts;
    }
}

/// <summary>
/// Tells each changed account's open apps (<see cref="LiveAreas.Event"/>). Changes are gathered for a second, so a burst
/// of saves (a todo and its occurrences, a movement and the limit alert it set off, a recurring pass) is one signal.
/// </summary>
internal sealed class LiveChangesPublisher(LiveChanges changes, IRealtimePublisher realtime, ILogger<LiveChangesPublisher> logger)
    : BackgroundService
{
    internal static readonly TimeSpan Gather = TimeSpan.FromSeconds(1);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        try
        {
            while (await changes.Changes.WaitToReadAsync(stoppingToken))
            {
                await Task.Delay(Gather, stoppingToken);
                var batch = new List<(string UserId, string Area)>();
                while (changes.Changes.TryRead(out var change))
                    batch.Add(change);

                foreach (var (userId, areas) in LiveChanges.PerAccount(batch))
                {
                    try
                    {
                        await realtime.PublishToUserAsync(userId, LiveAreas.Event, new { areas }, stoppingToken);
                    }
                    catch (Exception ex) when (ex is not OperationCanceledException)
                    {
                        logger.LogWarning(ex, "Could not tell user {UserId}'s open apps their data changed", userId);
                    }
                }
            }
        }
        catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
        {
        }
    }
}
