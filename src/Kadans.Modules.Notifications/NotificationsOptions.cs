namespace Kadans.Modules.Notifications;

internal sealed class NotificationsOptions
{
    public const string SectionName = "Notifications";

    /// <summary>How long the notification centre keeps a notification, read or not (<c>NotificationsRetentionJob</c>).</summary>
    public int RetentionDays { get; set; } = 30;
}
