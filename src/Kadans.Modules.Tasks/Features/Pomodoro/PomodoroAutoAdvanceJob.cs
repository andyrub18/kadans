using Kadans.Modules.Tasks.Contracts;
using Kadans.Modules.Tasks.Domain;
using Kadans.Modules.Tasks.Persistence;
using Kadans.SharedKernel.Notifications;
using Kadans.SharedKernel.Users;
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
    IUserDirectory users,
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
            // Oldest-due first: deterministic under the Take, and it walks the filtered index.
            .OrderBy(r => r.PhaseEndsAt)
            .Take(100)
            // Two collection loads (Phases, plus Todo's owned Remarks): split to avoid the join blow-up.
            .AsSplitQuery()
            .ToListAsync(cancellationToken);

        var languages = new Dictionary<string, string>();
        foreach (var run in due)
        {
            if (!languages.TryGetValue(run.UserId, out var language))
            {
                language = (await users.FindAsync(run.UserId, cancellationToken))?.Language ?? "en";
                languages[run.UserId] = language;
            }
            var texts = LocalizedTexts.Pomodoro(language);

            // Step on the schedule, not on job time: a run overdue by two phases lands where it
            // should be. The cap only guards a pathological backlog (looping runs never complete).
            var existingPhaseIds = run.Phases.Select(p => p.Id).ToHashSet();
            var steps = 0;
            while (run.Status == PomodoroRunStatus.Active && run.PhaseEndsAt <= now && steps++ < 500)
            {
                var advanced = run.Advance(expectedPhaseIndex: null, run.PhaseEndsAt!.Value);
                if (advanced.IsT0)
                    break;
            }

            PomodoroService.MarkNewPhasesAdded(dbContext, run, existingPhaseIds);
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

            var body = PhaseBody(
                texts,
                run.Status,
                run.CurrentPhase.Type,
                run.CurrentPhase.DurationMinutes,
                run.CurrentPhaseIndex,
                run.CycleLength,
                run.Loop
            );

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

    /// <summary>One phrasing for every phase notification, whoever advanced the run.</summary>
    internal static string PhaseBody(
        PomodoroTexts texts,
        PomodoroRunStatus status,
        PomodoroPhaseType currentType,
        int currentDurationMinutes,
        int currentPhaseIndex,
        int cycleLength,
        bool loop
    )
    {
        var lap = cycleLength > 0 ? currentPhaseIndex / cycleLength + 1 : 1;
        var lapPrefix = loop && lap > 1 ? string.Format(texts.LapFormat, lap) : "";
        return status == PomodoroRunStatus.Completed
            ? texts.Complete
            : currentType == PomodoroPhaseType.Break
                ? lapPrefix + string.Format(texts.BreakFormat, currentDurationMinutes)
                : lapPrefix + string.Format(texts.FocusFormat, currentDurationMinutes);
    }
}
