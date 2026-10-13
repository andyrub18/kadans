using Kadans.Modules.Tasks.Domain;
using Kadans.Modules.Tasks.Features.Reminders;
using Kadans.Modules.Tasks.Persistence;
using Kadans.SharedKernel.Notifications;
using Kadans.SharedKernel.Users;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Quartz;

namespace Kadans.Modules.Tasks.Features.Todos.Occurrences;

/// <summary>
/// Sends each pending occurrence's reminders, once each: the one before the start when <c>NotifyAt</c> has passed
/// (<c>NotifiedAt</c>), then "starts now" when the start has come (<c>StartNotifiedAt</c>), so the moment to begin never
/// passes in silence. A todo whose focus session is running gets neither: the person has begun. Occurrences already
/// long past are skipped silently rather than delivered late. A pass keeps going, 500 at a time, until nothing is due:
/// a peak (thousands of people's 08:00) goes out in one pass, each batch with one lookup of its people and one save,
/// instead of 500 every five seconds, one by one (docs/LOADTEST.md).
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
    public const string Kind = ReminderNotification.Kind;

    private const int BatchSize = 500;

    /// <summary>The longest a pass runs; whatever is still due is the next pass's, five seconds later.</summary>
    private static readonly TimeSpan MaxPass = TimeSpan.FromMinutes(2);

    public async Task Execute(IJobExecutionContext context)
    {
        var cancellationToken = context.CancellationToken;
        var started = DateTimeOffset.UtcNow;
        var staleBefore = started.AddMinutes(-options.Value.ReminderStaleAfterMinutes);

        // Too late to be useful: stamp them so the indexes stop returning them.
        var stale = await dbContext
            .TodoOccurrences.IgnoreQueryFilters()
            .Where(o => o.Status == OccurrenceStatus.Pending && o.NotifiedAt == null && o.NotifyAt != null && o.NotifyAt <= started && o.ScheduledAt < staleBefore)
            .ExecuteUpdateAsync(s => s.SetProperty(o => o.NotifiedAt, started).SetProperty(o => o.StartNotifiedAt, started), cancellationToken);
        stale += await dbContext
            .TodoOccurrences.IgnoreQueryFilters()
            .Where(o => o.Status == OccurrenceStatus.Pending && o.StartNotifiedAt == null && o.NotifyAt != null && o.ScheduledAt < staleBefore)
            .ExecuteUpdateAsync(s => s.SetProperty(o => o.StartNotifiedAt, started), cancellationToken);
        metrics.RemindersStale(stale);

        // The reminders before the start first: one whose start has come too goes out as "starts now" and stands for both.
        var sent = await SendDueAsync(atStart: false, started, cancellationToken);
        sent += await SendDueAsync(atStart: true, started, cancellationToken);

        if (sent > 0)
            logger.LogInformation("Reminder run: {Count} reminder(s) sent in {Seconds:0.0} s", sent, (DateTimeOffset.UtcNow - started).TotalSeconds);
    }

    /// <summary>The reminders due before the start (<paramref name="atStart"/> false), or the "starts now" ones; how many went out.</summary>
    private async Task<int> SendDueAsync(bool atStart, DateTimeOffset started, CancellationToken cancellationToken)
    {
        var sent = 0;
        while (DateTimeOffset.UtcNow - started < MaxPass)
        {
            var now = DateTimeOffset.UtcNow;
            var pending = dbContext.TodoOccurrences.IgnoreQueryFilters().Include(o => o.Todo).Where(o => o.Status == OccurrenceStatus.Pending && o.NotifyAt != null);
            var due = await (
                atStart
                    ? pending.Where(o => o.StartNotifiedAt == null && o.ScheduledAt <= now).OrderBy(o => o.ScheduledAt)
                    : pending.Where(o => o.NotifiedAt == null && o.NotifyAt <= now).OrderBy(o => o.NotifyAt)
            )
                .Take(BatchSize)
                .ToListAsync(cancellationToken);
            if (due.Count == 0)
                break;

            var accounts = due.Select(o => o.Todo!.UserId).Distinct().ToList();
            var people = await users.FindManyAsync(accounts, cancellationToken);
            // A phone whose window holds the account's latest version rings these itself: the push skips it.
            var versions = await dbContext
                .ReminderChanges.Where(c => accounts.Contains(c.UserId))
                .ToDictionaryAsync(c => c.UserId, c => c.Version, cancellationToken);
            var todos = due.Select(o => o.TodoId).Distinct().ToList();
            var focusing = (
                await dbContext
                    .PomodoroRuns.IgnoreQueryFilters()
                    .Where(r => todos.Contains(r.TodoId) && (r.Status == PomodoroRunStatus.Active || r.Status == PomodoroRunStatus.Paused))
                    .Select(r => r.TodoId)
                    .ToListAsync(cancellationToken)
            ).ToHashSet();

            var reminders = new List<UserNotification>(due.Count);
            var moments = new List<DateTimeOffset>(due.Count);
            foreach (var occurrence in due)
            {
                var moment = occurrence.ReminderMoment(now);
                occurrence.MarkReminded(now);
                if (focusing.Contains(occurrence.TodoId))
                    continue;
                var userId = occurrence.Todo!.UserId;
                var message = ReminderNotification.Message(occurrence, people.GetValueOrDefault(userId), now, versions.GetValueOrDefault(userId), moment);
                reminders.Add(new UserNotification(userId, message));
                moments.Add(moment);
            }

            if (reminders.Count > 0)
                await dispatcher.DispatchManyAsync(reminders, cancellationToken);
            await dbContext.SaveChangesAsync(cancellationToken);
            dbContext.ChangeTracker.Clear();

            var dispatched = DateTimeOffset.UtcNow;
            foreach (var moment in moments)
                metrics.ReminderSent(dispatched - moment);
            metrics.RemindersSkipped(due.Count - reminders.Count);
            sent += reminders.Count;

            if (due.Count < BatchSize)
                break;
        }
        return sent;
    }
}
