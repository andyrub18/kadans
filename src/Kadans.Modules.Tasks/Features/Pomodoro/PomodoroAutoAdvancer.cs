using Kadans.Modules.Tasks.Contracts;
using Kadans.Modules.Tasks.Domain;
using Kadans.Modules.Tasks.Persistence;
using Kadans.SharedKernel.Notifications;
using Kadans.SharedKernel.Realtime;
using Kadans.SharedKernel.Users;
using Microsoft.EntityFrameworkCore;

namespace Kadans.Modules.Tasks.Features.Pomodoro;

/// <summary>
/// Advances opted-in runs whose phase has run out, so the session keeps its cadence even when no
/// client is watching. Overdue runs (server downtime) are stepped phase by phase on the original
/// schedule; the user gets one broadcast and one notification describing where the run is now.
/// Driven by <see cref="PomodoroDeadlineWatcher"/>, which wakes up exactly when a phase ends.
/// </summary>
internal sealed class PomodoroAutoAdvancer(
    TasksDbContext dbContext,
    INotificationDispatcher dispatcher,
    IRealtimePublisher realtime,
    IUserDirectory users,
    ILogger<PomodoroAutoAdvancer> logger
)
{
    public const string Kind = "pomodoro.phase.completed";

    /// <summary>Steps every due run and returns when the next hands-free phase ends (null: none is running).</summary>
    public async Task<DateTimeOffset?> StepDueRunsAsync(DateTimeOffset now, CancellationToken cancellationToken)
    {
        var dueIds = await dbContext
            .PomodoroRuns.IgnoreQueryFilters()
            .Where(r => r.Status == PomodoroRunStatus.Active && r.AutoAdvance && r.PhaseEndsAt <= now)
            // Oldest-due first: deterministic under the Take, and it walks the filtered index.
            .OrderBy(r => r.PhaseEndsAt)
            .Take(100)
            .Select(r => r.Id)
            .ToListAsync(cancellationToken);

        var languages = new Dictionary<string, string>();
        var advanced = 0;
        foreach (var runId in dueIds)
        {
            // One run per unit of work: a conflict on one must not poison the others.
            dbContext.ChangeTracker.Clear();
            if (await StepAsync(runId, now, languages, cancellationToken))
                advanced++;
        }

        if (advanced > 0)
            logger.LogInformation("Auto-advanced {Count} pomodoro run(s)", advanced);

        return await dbContext
            .PomodoroRuns.IgnoreQueryFilters()
            .Where(r => r.Status == PomodoroRunStatus.Active && r.AutoAdvance && r.PhaseEndsAt > now)
            .MinAsync(r => r.PhaseEndsAt, cancellationToken);
    }

    private async Task<bool> StepAsync(Guid runId, DateTimeOffset now, Dictionary<string, string> languages, CancellationToken cancellationToken)
    {
        var run = await dbContext
            .PomodoroRuns.IgnoreQueryFilters()
            .Include(r => r.Phases)
            .Include(r => r.Todo)
            // Two collection loads (Phases, plus Todo's owned Remarks): split to avoid the join blow-up.
            .AsSplitQuery()
            .FirstOrDefaultAsync(r => r.Id == runId, cancellationToken);
        if (run is null || run.Status != PomodoroRunStatus.Active || run.PhaseEndsAt > now)
            return false; // a client got there between the scan and this load

        // Step on the schedule, not on wake-up time: a run overdue by two phases lands where it
        // should be. The cap only guards a pathological backlog (looping runs never complete).
        var existingPhaseIds = run.Phases.Select(p => p.Id).ToHashSet();
        var steps = 0;
        while (run.Status == PomodoroRunStatus.Active && run.PhaseEndsAt <= now && steps++ < 500)
        {
            if (run.Advance(expectedPhaseIndex: null, run.PhaseEndsAt!.Value).IsT0)
                break;
        }

        PomodoroService.MarkNewPhasesAdded(dbContext, run, existingPhaseIds);
        try
        {
            await dbContext.SaveChangesAsync(cancellationToken);
        }
        catch (DbUpdateConcurrencyException)
        {
            // The watching client advanced the same phase at the same instant and won the row
            // version: it broadcast and notified already. Exactly one of us may.
            logger.LogDebug("Run {RunId} was advanced by a client at the same moment", runId);
            return false;
        }

        try
        {
            await realtime.PublishToUserAsync(run.UserId, "pomodoro.run.changed", run.ToResponse(), cancellationToken);
        }
        catch (Exception ex)
        {
            logger.LogWarning(ex, "Could not broadcast run {RunId}", run.Id);
        }

        if (!languages.TryGetValue(run.UserId, out var language))
        {
            language = (await users.FindAsync(run.UserId, cancellationToken))?.Language ?? "en";
            languages[run.UserId] = language;
        }

        var body = PhaseBody(
            LocalizedTexts.Pomodoro(language),
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
        return true;
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
