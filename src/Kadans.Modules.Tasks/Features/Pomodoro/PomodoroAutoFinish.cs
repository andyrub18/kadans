using Kadans.Modules.Tasks.Contracts;
using Kadans.Modules.Tasks.Domain;
using Kadans.Modules.Tasks.Persistence;
using Kadans.SharedKernel.Notifications;
using Kadans.SharedKernel.Realtime;
using Kadans.SharedKernel.Users;
using Microsoft.EntityFrameworkCore;

namespace Kadans.Modules.Tasks.Features.Pomodoro;

/// <summary>
/// Sessions end by themselves at their end time (<see cref="PomodoroRun.FinishBy"/>, 12 hours after the start unless
/// chosen), or sooner when the next occurrence of their todo starts (<see cref="SessionOccurrence.NextStartAsync"/>):
/// as completed, as if Finish were pressed, so a session left running overnight neither notifies all night nor greets
/// the person the next morning, and a forgotten one never swallows the next occurrence. Driven by
/// <see cref="PomodoroDeadlineWatcher"/>; runs before the auto-advance, which never steps a run past its end time.
/// </summary>
internal sealed class PomodoroAutoFinish(
    TasksDbContext dbContext,
    INotificationDispatcher dispatcher,
    IRealtimePublisher realtime,
    IUserDirectory users,
    TasksMetrics metrics,
    ILogger<PomodoroAutoFinish> logger
)
{
    public const string Kind = "pomodoro.run.finished";

    /// <summary>An end passed this long ago (the server was down) finishes the run without a notification.</summary>
    internal static readonly TimeSpan StaleAfter = TimeSpan.FromMinutes(15);

    /// <summary>Finishes every run whose end has come; returns the next end (null: nothing is running).</summary>
    public async Task<DateTimeOffset?> FinishDueRunsAsync(DateTimeOffset now, CancellationToken cancellationToken)
    {
        var dueIds = await Ends()
            .Where(r => r.EndsAt <= now)
            .OrderBy(r => r.EndsAt)
            .Take(100)
            .Select(r => r.Id)
            .ToListAsync(cancellationToken);

        foreach (var runId in dueIds)
        {
            dbContext.ChangeTracker.Clear();
            await FinishIfDueAsync(runId, now, cancellationToken);
        }

        if (dueIds.Count > 0)
            logger.LogInformation("Finished {Count} pomodoro run(s) at their end", dueIds.Count);

        return await Ends().Where(r => r.EndsAt > now).MinAsync(r => (DateTimeOffset?)r.EndsAt, cancellationToken);
    }

    private IQueryable<PomodoroRun> Running() =>
        dbContext
            .PomodoroRuns.IgnoreQueryFilters()
            .Where(r => (r.Status == PomodoroRunStatus.Active || r.Status == PomodoroRunStatus.Paused) && r.FinishBy != null);

    /// <summary>Every running session and when it ends (<see cref="PomodoroRun.EndsAt"/>): one query for them all.</summary>
    private IQueryable<RunEnd> Ends() =>
        Running()
            .Select(r => new
            {
                r.Id,
                FinishBy = r.FinishBy!.Value,
                // SessionOccurrence.NextStartAsync, for every session at once.
                Next = dbContext
                    .TodoOccurrences.IgnoreQueryFilters()
                    .Where(o =>
                        o.TodoId == r.TodoId
                        && o.Status == OccurrenceStatus.Pending
                        && o.Id != r.OccurrenceId
                        && o.ScheduledAt > r.StartedAt
                    )
                    .Min(o => (DateTimeOffset?)o.ScheduledAt),
            })
            .Select(r => new RunEnd { Id = r.Id, EndsAt = r.Next != null && r.Next < r.FinishBy ? r.Next.Value : r.FinishBy });

    private sealed class RunEnd
    {
        public Guid Id { get; init; }
        public DateTimeOffset EndsAt { get; init; }
    }

    /// <summary>
    /// Ends the run if its end has come. Also asked by a session starting on the same todo: the watcher may not have
    /// ended the one before yet when its next occurrence's "Start focus" is pressed.
    /// </summary>
    public async Task FinishIfDueAsync(Guid runId, DateTimeOffset now, CancellationToken cancellationToken)
    {
        var run = await dbContext
            .PomodoroRuns.IgnoreQueryFilters()
            .Include(r => r.Phases)
            .Include(r => r.Todo)
            .AsSplitQuery()
            .FirstOrDefaultAsync(r => r.Id == runId, cancellationToken);
        if (run is null)
            return;
        var next = await SessionOccurrence.NextStartAsync(dbContext.TodoOccurrences.IgnoreQueryFilters(), run, cancellationToken);
        if (!run.FinishDue(now, next))
            return; // finished by hand, given a later end, or its next occurrence moved, between the scan and this load

        // On the end, not on wake-up time: the phase under way counts up to then.
        var end = run.EndsAt(next)!.Value;
        var byNext = next == end && end < run.FinishBy;
        var betweenLaps = run.IsBetweenLaps;
        if (run.Finish(end, onItsOwn: true).IsT0)
            return;

        try
        {
            await dbContext.SaveChangesAsync(cancellationToken);
        }
        catch (DbUpdateConcurrencyException)
        {
            logger.LogDebug("Run {RunId} changed while it was being finished at its end time", runId);
            return;
        }
        metrics.PomodoroDeadline("finish", DateTimeOffset.UtcNow - end);

        try
        {
            await realtime.PublishToUserAsync(run.UserId, "pomodoro.run.changed", run.ToResponse(), cancellationToken);
        }
        catch (Exception ex)
        {
            logger.LogWarning(ex, "Could not broadcast run {RunId}", run.Id);
        }

        // Said already, or about to be: "Another cycle?" when the lap ended, or the next occurrence's own "starts now".
        if (now - end > StaleAfter || betweenLaps || byNext && await NextRingsAsync(run, end, cancellationToken))
            return;

        var user = await users.FindAsync(run.UserId, cancellationToken);
        var zone = user is not null && TimeZoneInfo.TryFindSystemTimeZoneById(user.TimeZoneId, out var found) ? found : TimeZoneInfo.Utc;
        var texts = LocalizedTexts.Pomodoro(user?.Language);
        await dispatcher.DispatchAsync(
            run.UserId,
            new NotificationMessage(
                Kind,
                run.Todo.Title,
                string.Format(byNext ? texts.NextBeganFormat : texts.FinishedFormat, $"{TimeZoneInfo.ConvertTime(end, zone):HH:mm}"),
                new Dictionary<string, string>
                {
                    ["todoId"] = run.TodoId.ToString(),
                    ["runId"] = run.Id.ToString(),
                    ["status"] = run.Status.ToString(),
                    ["link"] = PomodoroAutoAdvancer.FocusLink(run.TodoId),
                }
            ),
            cancellationToken
        );
    }

    /// <summary>Whether the occurrence that ended the session at <paramref name="start"/> has its own reminders.</summary>
    private Task<bool> NextRingsAsync(PomodoroRun run, DateTimeOffset start, CancellationToken cancellationToken) =>
        dbContext
            .TodoOccurrences.IgnoreQueryFilters()
            .AnyAsync(
                o => o.TodoId == run.TodoId && o.Status == OccurrenceStatus.Pending && o.ScheduledAt == start && o.NotifyAt != null,
                cancellationToken
            );
}
