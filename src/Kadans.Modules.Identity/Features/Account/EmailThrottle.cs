namespace Kadans.Modules.Identity.Features.Account;

/// <summary>
/// At most one mail of a kind to the same address every <see cref="Window"/>. The per-client rate limit stops one
/// sender; this stops many senders, or one rotating addresses, from burying one inbox in reset or confirmation
/// mails. In memory on purpose: Kadans runs as exactly one instance, and a restart only allows one mail more.
/// </summary>
internal sealed class EmailThrottle(TimeProvider time)
{
    public static readonly TimeSpan Window = TimeSpan.FromMinutes(2);

    /// <summary>Past this many remembered addresses, expired ones are dropped before a new one is added.</summary>
    private const int PruneAbove = 10_000;

    private readonly Dictionary<string, DateTimeOffset> lastSent = new(StringComparer.Ordinal);
    private readonly Lock gate = new();

    /// <summary>True, and remembered, when a <paramref name="kind"/> mail may go to <paramref name="address"/> now.</summary>
    public bool TryAcquire(string kind, string address)
    {
        var key = $"{kind}:{address.Trim().ToUpperInvariant()}";
        var now = time.GetUtcNow();
        lock (gate)
        {
            if (lastSent.TryGetValue(key, out var last) && now - last < Window)
                return false;

            if (lastSent.Count >= PruneAbove)
            {
                foreach (var expired in lastSent.Where(entry => now - entry.Value >= Window).Select(entry => entry.Key).ToList())
                    lastSent.Remove(expired);
            }

            lastSent[key] = now;
            return true;
        }
    }
}
