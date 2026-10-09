namespace Kadans.Modules.Tasks.Contracts;

/// <param name="InstallationId">The device asking (it registered itself under this id).</param>
/// <param name="Days">How far ahead, 1 to 7 days; 7 when omitted.</param>
public sealed record ReminderSyncRequest(Guid InstallationId, int? Days = null);

/// <summary>
/// The reminders this device rings itself, soonest first, with the words to show. <paramref name="Through"/> is how
/// far they reach: the server pushes those, and anything changed since, only to devices that may not have them.
/// </summary>
public sealed record ReminderWindowResponse(DateTimeOffset SyncedAt, DateTimeOffset Through, IReadOnlyList<UpcomingReminder> Reminders);

public sealed record UpcomingReminder(Guid OccurrenceId, Guid TodoId, string Title, string Body, DateTimeOffset NotifyAt, DateTimeOffset StartsAt);

/// <summary>Whether a reminder still rings at <paramref name="NotifyAt"/>: false once its occurrence is gone, done or cancelled.</summary>
public sealed record ReminderCheckResponse(Guid OccurrenceId, bool Due, DateTimeOffset? NotifyAt);
