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

    public int ReminderIntervalSeconds { get; set; } = 60;

    /// <summary>Reminders for occurrences already this far in the past are skipped instead of sent late.</summary>
    public int ReminderStaleAfterMinutes { get; set; } = 60;
}
