namespace Kadans.SharedKernel.Users;

/// <summary>
/// Told when sign-in sessions end: sign-out, sign out everywhere, a password change or reset, deactivation, a
/// replayed refresh token. Identity calls every registered listener once the sessions are revoked; a module
/// holding something per session (the hub's live connections) lets it go.
/// </summary>
public interface ISessionEndListener
{
    /// <param name="sessionIds">The ended sessions, as access tokens carry them (<c>SessionClaim</c>).</param>
    void SessionsEnded(string userId, IReadOnlyCollection<string> sessionIds);
}
