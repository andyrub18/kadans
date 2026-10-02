using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.SignalR;

namespace Kadans.Modules.Notifications.Realtime;

/// <summary>
/// Server → client events for connected apps. Clients subscribe with their access token
/// (`?access_token=`); SignalR routes `Clients.User(id)` by the token's name-identifier claim,
/// so every device of a user receives the same events. Event names: <c>notification</c>,
/// <c>pomodoro.run.changed</c>. A connection closes when the sign-in session it opened with ends
/// (<see cref="HubConnections"/>).
/// </summary>
[Authorize]
internal sealed class KadansHub(HubConnections connections) : Hub
{
    public override Task OnConnectedAsync()
    {
        connections.Opened(Context);
        return base.OnConnectedAsync();
    }

    public override Task OnDisconnectedAsync(Exception? exception)
    {
        connections.Closed(Context.ConnectionId);
        return base.OnDisconnectedAsync(exception);
    }
}
