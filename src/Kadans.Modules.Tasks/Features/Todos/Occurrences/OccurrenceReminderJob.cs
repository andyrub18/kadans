using Kadans.Modules.Tasks.Domain;
using Kadans.Modules.Tasks.Features;
using Kadans.Modules.Tasks.Persistence;
using Kadans.SharedKernel.Notifications;
using Kadans.SharedKernel.Users;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Quartz;

namespace Kadans.Modules.Tasks.Features.Todos.Occurrences;

/// <summary>
/// Sends the "starts soon" reminder for pending occurrences whose <c>NotifyAt</c> has passed,
/// once each (<c>NotifiedAt</c>). Occurrences that are already long past are skipped silently
/// rather than delivered late. A pass keeps going, 500 at a time, until nothing is due: a peak
/// (thousands of people's 08:00) goes out in one pass, each batch with one lookup of its people
/// and one save, instead of 500 every five seconds, one by one (docs/LOADTEST.md).
/// </summary>
[DisallowConcurrentExecution]
internal sealed class OccurrenceReminderJob(
    TasksDbContext dbContext,
    INotificationDispatcher dispatcher,
    IUserDirectory users,
    IOptions<TasksOptions> options,
    TasksMetrics metrics,
    ILogger<OccurrenceReminderJob> logger
) : IJob
{
    public static readonly JobKey Key = new("occurrence-reminder", "tasks");
    public const string Kind = "occurrence.due";

    private const int BatchSize = 500;

    /// <summary>The longest a pass runs; whatever is still due is the next pass's, five seconds later.</summary>
    private static readonly TimeSpan MaxPass = TimeSpan.FromMinutes(2);

    public async Task Execute(IJobExecutionContext context)
    {
        var cancellationToken = context.CancellationToken;
        var started = DateTimeOffset.UtcNow;
        var staleBefore = started.AddMinutes(-options.Value.ReminderStaleAfterMinutes);

        // Too late to be useful: stamp them so the index stops returning them.
        var stale = await dbContext
            .TodoOccurrences.IgnoreQueryFilters()
            .Where(o => o.Status == OccurrenceStatus.Pending && o.NotifiedAt == null && o.NotifyAt != null && o.NotifyAt <= started && o.ScheduledAt < staleBefore)
            .ExecuteUpdateAsync(s => s.SetProperty(o => o.NotifiedAt, started), cancellationToken);
        metrics.RemindersStale(stale);

        var sent = 0;
        while (DateTimeOffset.UtcNow - started < MaxPass)
        {
            var now = DateTimeOffset.UtcNow;
            var due = await dbContext
                .TodoOccurrences.IgnoreQueryFilters()
                .Include(o => o.Todo)
                .Where(o => o.Status == OccurrenceStatus.Pending && o.NotifiedAt == null && o.NotifyAt != null && o.NotifyAt <= now)
                .OrderBy(o => o.NotifyAt)
                .Take(BatchSize)
                .ToListAsync(cancellationToken);
            if (due.Count == 0)
                break;

            var people = await users.FindManyAsync([.. due.Select(o => o.Todo!.UserId).Distinct()], cancellationToken);
            var reminders = new List<UserNotification>(due.Count);
            foreach (var occurrence in due)
            {
                reminders.Add(new UserNotification(occurrence.Todo!.UserId, Reminder(occurrence, people.GetValueOrDefault(occurrence.Todo.UserId), now)));
                occurrence.NotifiedAt = now;
            }

            await dispatcher.DispatchManyAsync(reminders, cancellationToken);
            await dbContext.SaveChangesAsync(cancellationToken);
            dbContext.ChangeTracker.Clear();

            var dispatched = DateTimeOffset.UtcNow;
            foreach (var occurrence in due)
                metrics.ReminderSent(dispatched - occurrence.NotifyAt!.Value);
            sent += due.Count;

            if (due.Count < BatchSize)
                break;
        }

        if (sent > 0)
            logger.LogInformation("Reminder run: {Count} reminder(s) sent in {Seconds:0.0} s", sent, (DateTimeOffset.UtcNow - started).TotalSeconds);
    }

    private static NotificationMessage Reminder(TodoOccurrence occurrence, UserSummary? user, DateTimeOffset now)
    {
        var todo = occurrence.Todo!;
        var timeZone = user is not null && TimeZoneInfo.TryFindSystemTimeZoneById(user.TimeZoneId, out var found) ? found : TimeZoneInfo.Utc;
        var texts = LocalizedTexts.Reminder(user?.Language ?? "en");
        var local = TimeZoneInfo.ConvertTime(occurrence.ScheduledAt, timeZone);
        var untilStart = occurrence.ScheduledAt - now;

        return new NotificationMessage(
            Kind,
            todo.Title,
            untilStart > TimeSpan.FromSeconds(30)
                ? string.Format(texts.StartsAtFormat, $"{local:HH:mm}", Describe(untilStart, texts))
                : string.Format(texts.StartsNowFormat, $"{local:HH:mm}"),
            new Dictionary<string, string>
            {
                ["todoId"] = todo.Id.ToString(),
                ["occurrenceId"] = occurrence.Id.ToString(),
                ["scheduledAt"] = occurrence.ScheduledAt.ToString("O"),
                ["pomodoroTemplateId"] = todo.PomodoroTemplateId?.ToString() ?? string.Empty,
            }
        );
    }

    /// <summary>
    /// "15 min", "2 h 05 min", "3 d 4 h" — unit words from the user's language. Rounded up to the minute: the job
    /// runs a few seconds after the notify time, when a 15-minute lead is 14 min 5x s away, and must still read
    /// "15 min" (and an hour "1 h", not "59 min").
    /// </summary>
    internal static string Describe(TimeSpan span, ReminderTexts texts)
    {
        const long minutesPerDay = 24 * 60;
        var minutes = Math.Max(1L, (long)Math.Ceiling(span.TotalMinutes));
        if (minutes < 60)
            return $"{minutes} {texts.MinuteAbbrev}";
        if (minutes < minutesPerDay)
            return minutes % 60 == 0
                ? $"{minutes / 60} {texts.HourAbbrev}"
                : $"{minutes / 60} {texts.HourAbbrev} {minutes % 60:00} {texts.MinuteAbbrev}";
        var hours = minutes % minutesPerDay / 60;
        return hours == 0
            ? $"{minutes / minutesPerDay} {texts.DayAbbrev}"
            : $"{minutes / minutesPerDay} {texts.DayAbbrev} {hours} {texts.HourAbbrev}";
    }
}
