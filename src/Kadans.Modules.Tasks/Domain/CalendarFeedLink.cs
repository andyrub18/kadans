using System.Security.Cryptography;

namespace Kadans.Modules.Tasks.Domain;

/// <summary>
/// The account's calendar feed is on: a calendar app holding its link reads the todos (ARCHITECTURE → "The calendar
/// feed"). One row per account. The database keeps only <see cref="Nonce"/>, random and not secret by itself: the
/// link's secret is that nonce signed with a key the database does not hold (<c>CalendarFeedLinks</c>), so a database
/// dump opens no feed. Resetting draws a new nonce: the old link names nothing any more.
/// </summary>
internal sealed class CalendarFeedLink
{
    public const int NonceSize = 16;

    public required string UserId { get; init; }
    public byte[] Nonce { get; private set; } = RandomNumberGenerator.GetBytes(NonceSize);
    public DateTimeOffset CreatedAt { get; private set; } = DateTimeOffset.UtcNow;

    /// <summary>The last time a calendar read the feed, to the minute: what tells the person their calendar follows.</summary>
    public DateTimeOffset? LastFetchedAt { get; private set; }

    /// <summary>A new link; whoever holds the old one reads nothing more.</summary>
    public void Reset(DateTimeOffset now)
    {
        Nonce = RandomNumberGenerator.GetBytes(NonceSize);
        CreatedAt = now;
        LastFetchedAt = null;
    }
}
