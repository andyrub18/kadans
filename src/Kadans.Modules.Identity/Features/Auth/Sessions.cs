using Kadans.Modules.Identity.Persistence;
using Kadans.Modules.Identity.Security;
using Kadans.SharedKernel.Users;
using Microsoft.EntityFrameworkCore;

namespace Kadans.Modules.Identity.Features.Auth;

/// <summary>What ending sessions did: the sessions revoked, and how many devices went with them.</summary>
internal sealed record EndedSessions(string UserId, IReadOnlyList<Guid> SessionIds, int Devices);

/// <summary>
/// Ends sign-in sessions. A session is one sign-in on one device: a refresh-token family, named in every access
/// token it hands out. Ending one revokes its refresh tokens, removes the device it registered (so a signed-out
/// phone stops getting reminders), refuses its access tokens from the next request on, and closes its live
/// connections. Every way a session ends goes through here: sign-out, sign out everywhere, a password change or
/// reset, deactivation, a replayed refresh token, an account taken over by its verified owner.
/// </summary>
internal sealed class Sessions(
    IdentityModuleDbContext dbContext,
    SessionRegistry registry,
    IEnumerable<ISessionEndListener> listeners,
    ILogger<Sessions> logger
)
{
    /// <summary>One session (a sign-out on one device), and the device it registered.</summary>
    public async Task<EndedSessions> EndAsync(string userId, Guid sessionId, string reason, CancellationToken cancellationToken = default)
    {
        await RevokeAsync(dbContext.RefreshTokens.Where(token => token.FamilyId == sessionId && token.IsActive), reason, cancellationToken);
        var devices = await dbContext.Devices.Where(device => device.SessionId == sessionId).ExecuteDeleteAsync(cancellationToken);
        return Announce(new EndedSessions(userId, [sessionId], devices));
    }

    /// <summary>Every session of the account, and every device: each registers again at its next sign-in.</summary>
    public async Task<EndedSessions> EndAllAsync(string userId, string reason, CancellationToken cancellationToken = default)
    {
        var active = dbContext.RefreshTokens.Where(token => token.UserId == userId && token.IsActive);
        var sessionIds = await active.Select(token => token.FamilyId).Distinct().ToListAsync(cancellationToken);
        await RevokeAsync(active, reason, cancellationToken);
        var devices = await dbContext.Devices.Where(device => device.UserId == userId).ExecuteDeleteAsync(cancellationToken);
        return Announce(new EndedSessions(userId, sessionIds, devices));
    }

    /// <summary>
    /// Refuses the ended sessions' access tokens and closes their connections. Ending sessions inside a
    /// transaction: call this again once it commits, because a request checked in between still read them as on.
    /// </summary>
    public EndedSessions Announce(EndedSessions ended)
    {
        if (ended.SessionIds.Count == 0)
            return ended;

        registry.Ended(ended.SessionIds);
        var sessionIds = ended.SessionIds.Select(id => id.ToString()).ToArray();
        foreach (var listener in listeners)
        {
            try
            {
                listener.SessionsEnded(ended.UserId, sessionIds);
            }
            catch (Exception ex)
            {
                logger.LogError(ex, "{Listener} failed on ended sessions of user {UserId}", listener.GetType().Name, ended.UserId);
            }
        }

        return ended;
    }

    private static Task<int> RevokeAsync(IQueryable<Domain.RefreshToken> tokens, string reason, CancellationToken cancellationToken) =>
        tokens.ExecuteUpdateAsync(
            setters =>
                setters
                    .SetProperty(token => token.IsActive, false)
                    .SetProperty(token => token.RevokedAtUtc, DateTimeOffset.UtcNow)
                    .SetProperty(token => token.RevokedReason, reason),
            cancellationToken
        );
}
