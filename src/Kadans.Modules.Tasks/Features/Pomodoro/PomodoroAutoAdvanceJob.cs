using Kadans.Modules.Tasks.Contracts;
using Kadans.Modules.Tasks.Domain;
using Kadans.Modules.Tasks.Persistence;
using Kadans.SharedKernel.Notifications;
using Kadans.SharedKernel.Realtime;
using Microsoft.EntityFrameworkCore;
using Quartz;

namespace Kadans.Modules.Tasks.Features.Pomodoro;

/// <summary>
/// Advances opted-in runs whose phase has run out, so the session keeps its cadence even when no
/// client is watching. Overdue runs (server downtime) are stepped phase by phase on the original
/// schedule; the user gets one broadcast and one notification describing where the run is now.
/// </summary>
[DisallowConcurrentExecution]
internal sealed class PomodoroAutoAdvanceJob(
    TasksDbContext dbContext,
    INotificationDispatcher dispatcher,
    IRealtimePublisher realtime,
    ILogger<PomodoroAutoAdvanceJob> logger
) : IJob
{
    public static readonly JobKey Key = new("pomodoro-auto-advance", "tasks");
    public const string Kind = "pomodoro.phase.completed";

    public async Task Execute(IJobExecutionContext context)
    {
        var cancellationToken = context.CancellationToken;
        var now = DateTimeOffset.UtcNow;

        var due = await dbContext
            .PomodoroRuns.IgnoreQueryFilters()
            .Include(r => r.Phases)
            .Include(r => r.Todo)
            .Where(r => r.Status == PomodoroRunStatus.Active && r.AutoAdvance && r.PhaseEndsAt <= now)
            .Take(100)
            .ToListAsync(cancellationToken);

        foreach (var run in due)
        {
            // Step on the schedule, not on job time: a run overdue by two phases lands where it should be.
            while (run.Status == PomodoroRunStatus.Active && run.PhaseEndsAt <= now)
            {
                var advanced = run.Advance(expectedPhaseIndex: null, run.PhaseEndsAt!.Value);
                if (advanced.IsT0)
                    break;
            }

            await dbContext.SaveChangesAsync(cancellationToken);

            var response = run.ToResponse();
            try
            {
                await realtime.PublishToUserAsync(run.UserId, "pomodoro.run.changed", response, cancellationToken);
            }
            catch (Exception ex)
            {
                logger.LogWarning(ex, "Could not broadcast run {RunId}", run.Id);
            }

            var body = run.Status == PomodoroRunStatus.Completed
                ? "Pomodoro complete. Well done!"
                : run.CurrentPhase.Type == PomodoroPhaseType.Break
                    ? $"Break — {run.CurrentPhase.DurationMinutes} min"
                    : $"Focus — {run.CurrentPhase.DurationMinutes} min";

            await dispatcher.DispatchAsync(
                run.UserId,
                new NotificationMessage(
                    Kind,
                    run.Todo.Title,
                    body,
                    new Dictionary<string, string>
                    {
                        ["todoId"] = run.TodoId.ToString(),
                        ["runId"] = run.Id.ToString(),
                        ["status"] = run.Status.ToString(),
                        ["currentPhaseIndex"] = run.CurrentPhaseIndex.ToString(),
                    }
                ),
                cancellationToken
            );
        }

        if (due.Count > 0)
            logger.LogInformation("Auto-advanced {Count} pomodoro run(s)", due.Count);
    }
}
