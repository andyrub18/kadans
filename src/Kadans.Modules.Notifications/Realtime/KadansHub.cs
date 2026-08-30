using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.SignalR;

namespace Kadans.Modules.Notifications.Realtime;

/// <summary>
/// Server → client events for connected apps. Clients subscribe with their access token
/// (`?access_token=`); SignalR routes `Clients.User(id)` by the token's name-identifier claim,
/// so every device of a user receives the same events. Event names: <c>notification</c>,
/// <c>pomodoro.run.changed</c>.
/// </summary>
[Authorize]
internal sealed class KadansHub : Hub;
