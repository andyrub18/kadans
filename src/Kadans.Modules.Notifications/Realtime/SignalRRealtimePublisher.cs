using Kadans.SharedKernel.Realtime;
using Microsoft.AspNetCore.SignalR;

namespace Kadans.Modules.Notifications.Realtime;

internal sealed class SignalRRealtimePublisher(IHubContext<KadansHub> hub, ILogger<SignalRRealtimePublisher> logger) : IRealtimePublisher
{
    public async Task PublishToUserAsync(string userId, string eventName, object payload, CancellationToken cancellationToken = default)
    {
        await hub.Clients.User(userId).SendAsync(eventName, payload, cancellationToken);
        logger.LogDebug("Realtime {Event} published to user {UserId}", eventName, userId);
    }
}
