namespace Kadans.Modules.Notifications.Domain;

/// <summary>What was told to a user, kept so clients can show a notification centre and mark items read.</summary>
internal sealed class Notification
{
    public Guid Id { get; init; } = Guid.CreateVersion7();
    public required string UserId { get; init; }
    public required string Kind { get; init; }
    public required string Title { get; init; }
    public required string Body { get; init; }
    public string? DataJson { get; init; }
    public DateTimeOffset CreatedAt { get; init; } = DateTimeOffset.UtcNow;
    public DateTimeOffset? ReadAt { get; set; }
}
