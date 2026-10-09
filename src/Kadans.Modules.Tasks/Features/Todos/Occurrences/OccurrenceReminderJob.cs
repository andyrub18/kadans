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
    public const string Kind = ReminderNotification.Kind;

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

            var accounts = due.Select(o => o.Todo!.UserId).Distinct().ToList();
            var people = await users.FindManyAsync(accounts, cancellationToken);
            // A phone whose window holds the account's latest version rings these itself: the push skips it.
            var versions = await dbContext
                .ReminderChanges.Where(c => accounts.Contains(c.UserId))
                .ToDictionaryAsync(c => c.UserId, c => c.Version, cancellationToken);
            var reminders = new List<UserNotification>(due.Count);
            foreach (var occurrence in due)
            {
                var userId = occurrence.Todo!.UserId;
                var message = ReminderNotification.Message(
                    occurrence,
                    people.GetValueOrDefault(userId),
                    now,
                    versions.GetValueOrDefault(userId)
                );
                reminders.Add(new UserNotification(userId, message));
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
}
