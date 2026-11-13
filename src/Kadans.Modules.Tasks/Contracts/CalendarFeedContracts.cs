namespace Kadans.Modules.Tasks.Contracts;

/// <summary>
/// The account's calendar feed. On: <paramref name="Url"/> is the link a calendar app subscribes to (always https),
/// secret, whoever holds it reads the todos' titles and times. <paramref name="LastFetchedAt"/>: the last time a
/// calendar read it, null while none has. <paramref name="Available"/>: false for an account without the phones'
/// subscription, which the feed is part of: it cannot be turned on, and a link it already has gives its calendars an
/// empty calendar until the subscription is back.
/// </summary>
public sealed record CalendarFeedResponse(
    bool Enabled,
    string? Url = null,
    DateTimeOffset? CreatedAt = null,
    DateTimeOffset? LastFetchedAt = null,
    bool Available = true
);
