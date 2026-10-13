namespace Kadans.Modules.Tasks.Contracts;

/// <param name="InstallationId">The device asking (it registered itself under this id).</param>
/// <param name="Days">How far ahead, 1 to 7 days; 7 when omitted.</param>
public sealed record ReminderSyncRequest(Guid InstallationId, int? Days = null);

/// <summary>
/// The reminders this device rings itself, soonest first, with the words to show. <paramref name="Through"/> is how
/// far they reach: the server pushes those, and anything changed since, only to devices that may not have them.
/// </summary>
public sealed record ReminderWindowResponse(DateTimeOffset SyncedAt, DateTimeOffset Through, IReadOnlyList<UpcomingReminder> Reminders);

/// <summary>
/// One reminder: an occurrence rings before its start (<paramref name="NotifyAt"/>, its lead) and at its start ("starts
/// now", <paramref name="NotifyAt"/> = <paramref name="StartsAt"/>), two entries of the window; a zero lead is the start
/// alone. <paramref name="PomodoroTemplateId"/>: a focus todo's, whose reminder offers "Start focus" instead of "Done".
/// </summary>
public sealed record UpcomingReminder(
    Guid OccurrenceId,
    Guid TodoId,
    string Title,
    string Body,
    DateTimeOffset NotifyAt,
    DateTimeOffset StartsAt,
    Guid? PomodoroTemplateId = null
);

/// <summary>
/// Whether a reminder still rings: asked with its time, false once its occurrence is gone, done or cancelled, moved
/// (that time is no longer one of its reminders), or a focus session on the todo is running. Without a time (apps from
/// before the "starts now" reminder), whether the occurrence still has a reminder. <paramref name="NotifyAt"/> and
/// <paramref name="StartsAt"/>: its reminder and its start now, null once it has none.
/// </summary>
public sealed record ReminderCheckResponse(Guid OccurrenceId, bool Due, DateTimeOffset? NotifyAt, DateTimeOffset? StartsAt = null);
