namespace Kadans.SharedKernel.Notifications;

/// <summary>A user-facing notification: stored, pushed to devices and broadcast to connected clients.</summary>
/// <param name="ShownByApp">
/// The app builds this notification itself, with its buttons (a focus session's new cycle: "Finish session"), where it
/// can: a phone that rings its reminders gets it as data. Any other device gets the notification as it is.
/// </param>
public sealed record NotificationMessage(
    string Kind,
    string Title,
    string Body,
    IReadOnlyDictionary<string, string>? Data = null,
    ReminderDelivery? Reminder = null,
    bool ShownByApp = false
);

/// <summary>
/// A todo reminder, which phones may also ring themselves (ARCHITECTURE → "Reminders ring on the phone"): the push
/// skips a device whose reminder window holds the account's latest change and reaches <paramref name="NotifyAt"/>, the
/// moment this reminder rings (before the start, or the start itself). It is of use until <see cref="UsefulUntil"/>.
/// </summary>
/// <param name="AccountVersion">The account's reminders version: one more for each change a person makes; 0 before any.</param>
public sealed record ReminderDelivery(Guid OccurrenceId, DateTimeOffset NotifyAt, DateTimeOffset StartsAt, long AccountVersion)
{
    /// <summary>How long after its moment a reminder still helps when it comes late: the apps' own rule (LocalReminders.LATE).</summary>
    public static readonly TimeSpan Late = TimeSpan.FromMinutes(10);

    /// <summary>Until the start, or <see cref="Late"/> after its moment when that is later (a "starts now" reminder).</summary>
    public DateTimeOffset UsefulUntil => StartsAt > NotifyAt + Late ? StartsAt : NotifyAt + Late;
}

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
