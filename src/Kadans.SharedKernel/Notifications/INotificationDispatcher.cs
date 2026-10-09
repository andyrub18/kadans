namespace Kadans.SharedKernel.Notifications;

/// <summary>A user-facing notification: stored, pushed to devices and broadcast to connected clients.</summary>
public sealed record NotificationMessage(
    string Kind,
    string Title,
    string Body,
    IReadOnlyDictionary<string, string>? Data = null,
    ReminderDelivery? Reminder = null
);

/// <summary>
/// A todo reminder, which phones may also ring themselves (ARCHITECTURE → "Reminders ring on the phone"): the push
/// skips a device whose reminder window holds the account's latest change and reaches <paramref name="NotifyAt"/>. It
/// is no use once <paramref name="StartsAt"/> has passed.
/// </summary>
/// <param name="AccountVersion">The account's reminders version: one more for each change a person makes; 0 before any.</param>
public sealed record ReminderDelivery(Guid OccurrenceId, DateTimeOffset NotifyAt, DateTimeOffset StartsAt, long AccountVersion);

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

    /// <summary>
    /// Tells the account's apps that something changed, without a notification: live to connected ones
    /// (<paramref name="kind"/> is the hub event), and a silent push to the phones that schedule their own reminders.
    /// Nothing is stored.
    /// </summary>
    Task SignalAsync(string userId, string kind, CancellationToken cancellationToken = default);
}
