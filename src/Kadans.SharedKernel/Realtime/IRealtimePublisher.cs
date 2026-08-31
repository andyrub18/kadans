namespace Kadans.SharedKernel.Realtime;

public static class RealtimeHub
{
    /// <summary>Where the SignalR hub is mapped; the JWT bearer handler reads `access_token` from the query on this path.</summary>
    public const string Path = "/hubs/kadans";
}

/// <summary>Pushes a live event to every connected client of a user (desktop, phone…).</summary>
public interface IRealtimePublisher
{
    Task PublishToUserAsync(string userId, string eventName, object payload, CancellationToken cancellationToken = default);
}
