namespace Kadans.SharedKernel.Notifications;

/// <summary>
/// The sound a notification asks for (docs/ARCHITECTURE.md → "Sounds"): said in its data under <see cref="DataKey"/>,
/// so that a desktop app plays it and a phone shows the notification in the channel that has it. A notification that
/// says nothing sounds as the device's ordinary notifications do. Reminders have their own way: a phone's choice,
/// <see cref="Kadans.SharedKernel.Users.PushTarget.AlarmReminders"/>.
/// </summary>
public static class NotificationSounds
{
    public const string DataKey = "sound";

    /// <summary>A focus phase is over (a break begins, or the session is done).</summary>
    public const string FocusOver = "focus-over";

    /// <summary>A break is over: back to it.</summary>
    public const string BreakOver = "break-over";
}
