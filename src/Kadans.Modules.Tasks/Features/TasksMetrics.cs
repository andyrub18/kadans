using System.Diagnostics.Metrics;

namespace Kadans.Modules.Tasks.Features;

/// <summary>
/// What a person would feel first if the server fell behind (ARCHITECTURE → Observability): how late reminders go out,
/// and how late a Pomodoro phase change, time's up or session end happens.
/// </summary>
internal sealed class TasksMetrics
{
    public const string MeterName = "Kadans.Tasks";

    // From "on time" to "useless": the target is a reminder handed to push within a minute of its moment.
    private static readonly InstrumentAdvice<double> Lateness = new()
    {
        HistogramBucketBoundaries = [0.1, 0.25, 0.5, 1, 2, 5, 10, 15, 30, 45, 60, 90, 120, 300, 600, 900],
    };

    private readonly Counter<long> remindersSent;
    private readonly Counter<long> remindersStale;
    private readonly Histogram<double> reminderLateness;
    private readonly Histogram<double> pomodoroLateness;

    public TasksMetrics(IMeterFactory meters)
    {
        var meter = meters.Create(MeterName);
        remindersSent = meter.CreateCounter<long>("kadans.reminders.sent", "{reminder}", "Reminders handed to the notification dispatcher");
        remindersStale = meter.CreateCounter<long>("kadans.reminders.stale", "{reminder}", "Reminders skipped because their occurrence was already long past");
        reminderLateness = meter.CreateHistogram<double>("kadans.reminder.lateness", "s", "From a reminder's notify time to its dispatch", advice: Lateness);
        pomodoroLateness = meter.CreateHistogram<double>(
            "kadans.pomodoro.deadline.lateness",
            "s",
            "From a Pomodoro deadline to the server acting on it, by kind (advance, time_up, finish)",
            advice: Lateness
        );
    }

    public void ReminderSent(TimeSpan lateness)
    {
        remindersSent.Add(1);
        reminderLateness.Record(Math.Max(0, lateness.TotalSeconds));
    }

    public void RemindersStale(int count)
    {
        if (count > 0)
            remindersStale.Add(count);
    }

    public void PomodoroDeadline(string kind, TimeSpan lateness) =>
        pomodoroLateness.Record(Math.Max(0, lateness.TotalSeconds), new KeyValuePair<string, object?>("kind", kind));
}
