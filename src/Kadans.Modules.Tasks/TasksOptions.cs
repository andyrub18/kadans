namespace Kadans.Modules.Tasks;

internal sealed class TasksOptions
{
    public const string SectionName = "Tasks";

    /// <summary>Occurrence rows are kept materialized this far ahead; beyond it, calendars get computed previews.</summary>
    public int OccurrenceHorizonDays { get; set; } = 30;

    /// <summary>Upper bound per todo per generation pass (a minutely rule would otherwise create 43k rows at once).</summary>
    public int MaxOccurrencesPerBatch { get; set; } = 1000;

    public int HorizonRefreshMinutes { get; set; } = 60;

    public int MaxPreviewPerTodo { get; set; } = 500;

    /// <summary>
    /// How often the reminder job looks for due reminders, which is also the most a reminder can be late. The
    /// scan reads a filtered index (pending, not yet notified), which keeps a short interval cheap. 5 s is also
    /// the floor the scheduler accepts.
    /// </summary>
    public int ReminderIntervalSeconds { get; set; } = 5;

    /// <summary>
    /// Longest the deadline watcher sleeps without re-checking. Phase changes themselves are not on this
    /// grid: the watcher wakes exactly when the nearest hands-free phase ends.
    /// </summary>
    public int PomodoroAutoAdvanceSeconds { get; set; } = 5;

    /// <summary>Reminders for occurrences already this far in the past are skipped instead of sent late.</summary>
    public int ReminderStaleAfterMinutes { get; set; } = 60;

    /// <summary>How long occurrences nobody acted on are kept (<c>TasksRetentionJob</c>).</summary>
    public int UntouchedOccurrenceRetentionDays { get; set; } = 90;

    /// <summary>The calendar feed (ARCHITECTURE → "The calendar feed") holds the todos' times from this many days back…</summary>
    public int CalendarFeedPastDays { get; set; } = 30;

    /// <summary>…to this many days ahead: past the stored occurrences, the times the rules give.</summary>
    public int CalendarFeedAheadDays { get; set; } = 90;

    /// <summary>The most events one feed holds; over it, the ones to come are kept first. Google refuses a file past about 1 MB.</summary>
    public int CalendarFeedMaxEvents { get; set; } = 2000;

    /// <summary>How often the feed invites a calendar app to read it again (Apple's and Outlook's listen; Google's reads when it wants).</summary>
    public int CalendarFeedRefreshMinutes { get; set; } = 15;

    /// <summary>
    /// What one link may be read in an hour, wherever from; beyond it, 429 until the next one is due. Several devices
    /// each reading every five minutes stay under it.
    /// </summary>
    public int CalendarFeedFetchesPerHour { get; set; } = 60;
}
