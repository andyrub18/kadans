namespace Kadans.Modules.Tasks.Domain;

/// <summary>
/// The account's reminders version, one more for each change a person makes to what a phone may have scheduled
/// (ARCHITECTURE → "Reminders ring on the phone"). A device whose window holds an older version may hold a stale copy,
/// so the server still pushes to it.
/// </summary>
internal sealed class ReminderChange
{
    public required string UserId { get; init; }
    public long Version { get; set; }
    public DateTimeOffset ChangedAt { get; set; }
}
