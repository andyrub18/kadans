namespace Kadans.SharedKernel.Notifications;

/// <summary>A user-facing notification: stored, pushed to devices and broadcast to connected clients.</summary>
public sealed record NotificationMessage(
    string Kind,
    string Title,
    string Body,
    IReadOnlyDictionary<string, string>? Data = null
);

public interface INotificationDispatcher
{
    Task DispatchAsync(string userId, NotificationMessage message, CancellationToken cancellationToken = default);
}
