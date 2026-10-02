using System.Collections.Concurrent;
using System.Security.Claims;
using Kadans.SharedKernel.Security;
using Kadans.SharedKernel.Users;
using Microsoft.AspNetCore.SignalR;

namespace Kadans.Modules.Notifications.Realtime;

/// <summary>
/// The hub's open connections and the sign-in session each was opened with. A connection is authorized once, when
/// it opens; when its session ends (sign-out, sign out everywhere, password change…) it is closed here, so a
/// signed-out device stops receiving live events at once rather than whenever it next reconnects.
/// </summary>
internal sealed class HubConnections(ILogger<HubConnections> logger) : ISessionEndListener
{
    private readonly ConcurrentDictionary<string, (string? SessionId, HubCallerContext Context)> open = new();

    public int Count => open.Count;

    public void Opened(HubCallerContext context) =>
        open[context.ConnectionId] = (context.User?.FindFirstValue(SessionClaim.Type), context);

    public void Closed(string connectionId) => open.TryRemove(connectionId, out _);

    public void SessionsEnded(string userId, IReadOnlyCollection<string> sessionIds)
    {
        var closed = 0;
        foreach (var (connectionId, (sessionId, context)) in open)
        {
            if (sessionId is null || !sessionIds.Contains(sessionId))
                continue;

            open.TryRemove(connectionId, out _);
            context.Abort();
            closed++;
        }

        if (closed > 0)
            logger.LogInformation("Closed {Count} live connection(s) of user {UserId}: their session ended", closed, userId);
    }
}
