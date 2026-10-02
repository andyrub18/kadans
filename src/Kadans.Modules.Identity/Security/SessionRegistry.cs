using System.Collections.Concurrent;
using Kadans.Modules.Identity.Persistence;
using Microsoft.EntityFrameworkCore;

namespace Kadans.Modules.Identity.Security;

/// <summary>
/// Answers "is this sign-in session still on?" for every authenticated request, so an access token stops working
/// the moment its session ends instead of when it expires. A session is on while its refresh-token family holds an
/// unexpired active token. Answers come from memory: a session is read from the database at most once per
/// <see cref="CacheFor"/>, and an ended one is dropped the moment it ends (Kadans runs as exactly one instance, so
/// every ending passes through <see cref="Ended"/>). A read already under way when a session ends does not put its
/// stale answer back.
/// </summary>
internal sealed class SessionRegistry(IServiceScopeFactory scopes, TimeProvider time)
{
    internal static readonly TimeSpan CacheFor = TimeSpan.FromMinutes(1);

    /// <summary>Sessions known to be on, until when that answer holds.</summary>
    private readonly ConcurrentDictionary<Guid, DateTimeOffset> on = new();

    /// <summary>Bumped by every ending; a read that saw it change keeps its answer to itself.</summary>
    private long endings;

    private long nextSweepTicks;

    public async ValueTask<bool> IsOnAsync(Guid sessionId, CancellationToken cancellationToken = default)
    {
        var now = time.GetUtcNow();
        if (on.TryGetValue(sessionId, out var until) && until > now)
            return true;

        var endingsBefore = Interlocked.Read(ref endings);
        List<DateTimeOffset> expiries;
        await using (var scope = scopes.CreateAsyncScope())
        {
            expiries = await scope
                .ServiceProvider.GetRequiredService<IdentityModuleDbContext>()
                .RefreshTokens.Where(token => token.FamilyId == sessionId && token.IsActive)
                .Select(token => token.ExpireAtUtc)
                .ToListAsync(cancellationToken);
        }

        var isOn = expiries.Any(expiry => expiry > now);
        if (isOn && Interlocked.Read(ref endings) == endingsBefore)
        {
            on[sessionId] = now + CacheFor;
            Sweep(now);
        }
        else
        {
            on.TryRemove(sessionId, out _);
        }

        return isOn;
    }

    /// <summary>These sessions have ended: their next request is refused.</summary>
    public void Ended(IEnumerable<Guid> sessionIds)
    {
        Interlocked.Increment(ref endings);
        foreach (var sessionId in sessionIds)
            on.TryRemove(sessionId, out _);
    }

    /// <summary>Forget answers that ran out, once a minute: the map holds the sessions active lately, not all ever seen.</summary>
    private void Sweep(DateTimeOffset now)
    {
        var due = Interlocked.Read(ref nextSweepTicks);
        if (now.UtcTicks < due || Interlocked.CompareExchange(ref nextSweepTicks, (now + CacheFor).UtcTicks, due) != due)
            return;
        foreach (var (sessionId, until) in on)
        {
            if (until <= now)
                on.TryRemove(sessionId, out _);
        }
    }
}
