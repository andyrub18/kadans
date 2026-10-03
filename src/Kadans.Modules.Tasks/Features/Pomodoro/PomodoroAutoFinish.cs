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
/// chosen): as completed, as if Finish were pressed, so a session left running overnight neither notifies all night
/// nor greets the person the next morning. Driven by <see cref="PomodoroDeadlineWatcher"/>; runs before the
/// auto-advance, which never steps a run past its end.
/// </summary>
internal sealed class PomodoroAutoFinish(
    TasksDbContext dbContext,
    INotificationDispatcher dispatcher,
    IRealtimePublisher realtime,
    IUserDirectory users,
    ILogger<PomodoroAutoFinish> logger
)
{
    public const string Kind = "pomodoro.run.finished";

    /// <summary>An end passed this long ago (the server was down) finishes the run without a notification.</summary>
    internal static readonly TimeSpan StaleAfter = TimeSpan.FromMinutes(15);

    /// <summary>Finishes every run whose end has come; returns the next end (null: nothing is running).</summary>
    public async Task<DateTimeOffset?> FinishDueRunsAsync(DateTimeOffset now, CancellationToken cancellationToken)
    {
        var dueIds = await Running()
            .Where(r => r.FinishBy <= now)
            .OrderBy(r => r.FinishBy)
            .Take(100)
            .Select(r => r.Id)
            .ToListAsync(cancellationToken);

        foreach (var runId in dueIds)
        {
            dbContext.ChangeTracker.Clear();
            await FinishAsync(runId, now, cancellationToken);
        }

        if (dueIds.Count > 0)
            logger.LogInformation("Finished {Count} pomodoro run(s) at their end time", dueIds.Count);

        return await Running().Where(r => r.FinishBy > now).MinAsync(r => r.FinishBy, cancellationToken);
    }

    private IQueryable<PomodoroRun> Running() =>
        dbContext
            .PomodoroRuns.IgnoreQueryFilters()
            .Where(r => (r.Status == PomodoroRunStatus.Active || r.Status == PomodoroRunStatus.Paused) && r.FinishBy != null);

    private async Task FinishAsync(Guid runId, DateTimeOffset now, CancellationToken cancellationToken)
    {
        var run = await dbContext
            .PomodoroRuns.IgnoreQueryFilters()
            .Include(r => r.Phases)
            .Include(r => r.Todo)
            .AsSplitQuery()
            .FirstOrDefaultAsync(r => r.Id == runId, cancellationToken);
        if (run is null || !run.FinishDue(now))
            return; // finished by hand, or given a later end, between the scan and this load

        // On the end time, not on wake-up time: the phase under way counts up to then.
        var end = run.FinishBy!.Value;
        if (run.Finish(end).IsT0)
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

        try
        {
            await realtime.PublishToUserAsync(run.UserId, "pomodoro.run.changed", run.ToResponse(), cancellationToken);
        }
        catch (Exception ex)
        {
            logger.LogWarning(ex, "Could not broadcast run {RunId}", run.Id);
        }

        if (now - end > StaleAfter)
            return;

        var user = await users.FindAsync(run.UserId, cancellationToken);
        var zone = user is not null && TimeZoneInfo.TryFindSystemTimeZoneById(user.TimeZoneId, out var found) ? found : TimeZoneInfo.Utc;
        var texts = LocalizedTexts.Pomodoro(user?.Language);
        await dispatcher.DispatchAsync(
            run.UserId,
            new NotificationMessage(
                Kind,
                run.Todo.Title,
                string.Format(texts.FinishedFormat, $"{TimeZoneInfo.ConvertTime(end, zone):HH:mm}"),
                new Dictionary<string, string>
                {
                    ["todoId"] = run.TodoId.ToString(),
                    ["runId"] = run.Id.ToString(),
                    ["status"] = run.Status.ToString(),
                }
            ),
            cancellationToken
        );
    }
}
