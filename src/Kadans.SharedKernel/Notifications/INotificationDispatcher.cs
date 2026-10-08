namespace Kadans.SharedKernel.Notifications;

/// <summary>A user-facing notification: stored, pushed to devices and broadcast to connected clients.</summary>
public sealed record NotificationMessage(
    string Kind,
    string Title,
    string Body,
    IReadOnlyDictionary<string, string>? Data = null
);

/// <summary>One notification for one account.</summary>
public sealed record UserNotification(string UserId, NotificationMessage Message);

public interface INotificationDispatcher
{
    Task DispatchAsync(string userId, NotificationMessage message, CancellationToken cancellationToken = default);

    /// <summary>
    /// Many at once (a reminder peak): stored in one save, each sent live and queued for push. What
    /// <see cref="DispatchAsync"/> does one by one, without a database round trip per notification.
    /// </summary>
    Task DispatchManyAsync(IReadOnlyList<UserNotification> notifications, CancellationToken cancellationToken = default);
}
