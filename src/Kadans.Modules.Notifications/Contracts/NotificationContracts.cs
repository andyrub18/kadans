namespace Kadans.Modules.Notifications.Contracts;

public sealed record NotificationResponse(
    Guid Id,
    string Kind,
    string Title,
    string Body,
    IReadOnlyDictionary<string, string>? Data,
    DateTimeOffset CreatedAt,
    DateTimeOffset? ReadAt
);

public sealed record UnreadCountResponse(int Unread);
