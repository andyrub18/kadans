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
/// A looping run goes on into its next cycle, and that notification offers to finish the session (<see cref="CycleDone"/>).
/// Driven by <see cref="PomodoroDeadlineWatcher"/>, which wakes up exactly when a phase ends.
/// </summary>
internal sealed class PomodoroAutoAdvancer(
    TasksDbContext dbContext,
    INotificationDispatcher dispatcher,
    IRealtimePublisher realtime,
    IUserDirectory users,
    TasksMetrics metrics,
    ILogger<PomodoroAutoAdvancer> logger
)
{
    public const string Kind = "pomodoro.phase.completed";

    /// <summary>A pomodoro notification opens its session (the app's focus link).</summary>
    internal static string FocusLink(Guid todoId) => $"kadans://todos/{todoId}/focus";

    /// <summary>
    /// The phase notification's mark that a looping run began its next cycle: the number of the one just done. The apps
    /// then add "Finish session" to it (ARCHITECTURE → Pomodoro).
    /// </summary>
    public const string CycleDoneKey = "cycleDone";

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
        if (run is null || run.Status != PomodoroRunStatus.Active || run.PhaseEndsAt > now || run.PhaseEndsAt >= run.FinishBy)
            return false; // a client got there between the scan and this load, or the session ends first

        // Step on the schedule, not on wake-up time: a run overdue by two phases lands where it
        // should be. The cap only guards a pathological backlog (looping runs never complete).
        var existingPhaseIds = run.Phases.Select(p => p.Id).ToHashSet();
        var dueAt = run.PhaseEndsAt!.Value;
        var steps = 0;
        // Never past the session's end: PomodoroAutoFinish ends it there.
        while (run.Status == PomodoroRunStatus.Active && run.PhaseEndsAt <= now && !(run.PhaseEndsAt >= run.FinishBy) && steps++ < 500)
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
        metrics.PomodoroDeadline("advance", DateTimeOffset.UtcNow - dueAt);

        try
        {
            var next = run.IsRunning
                ? await SessionOccurrence.NextStartAsync(dbContext.TodoOccurrences.IgnoreQueryFilters(), run, cancellationToken)
                : null;
            await realtime.PublishToUserAsync(run.UserId, "pomodoro.run.changed", run.ToResponse(next), cancellationToken);
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

        var phase = run.CurrentPhase;
        await dispatcher.DispatchAsync(
            run.UserId,
            PhaseMessage(
                LocalizedTexts.Pomodoro(language), run.Todo.Title, run.TodoId, run.Id,
                run.Status, phase.Type, phase.DurationMinutes, run.CurrentPhaseIndex, run.CycleLength, run.Loop
            ),
            cancellationToken
        );
        return true;
    }

    /// <summary>One notification for every phase change, whoever advanced the run (this, or a watching app).</summary>
    internal static NotificationMessage PhaseMessage(
        PomodoroTexts texts,
        string title,
        Guid todoId,
        Guid runId,
        PomodoroRunStatus status,
        PomodoroPhaseType currentType,
        int currentDurationMinutes,
        int currentPhaseIndex,
        int cycleLength,
        bool loop
    )
    {
        var data = new Dictionary<string, string>
        {
            ["todoId"] = todoId.ToString(),
            ["runId"] = runId.ToString(),
            ["status"] = status.ToString(),
            ["currentPhaseIndex"] = currentPhaseIndex.ToString(),
            ["link"] = FocusLink(todoId),
        };
        var cycleDone = CycleDone(status, currentPhaseIndex, cycleLength, loop);
        if (cycleDone is { } done)
            data[CycleDoneKey] = done.ToString();
        var body = PhaseBody(texts, status, currentType, currentDurationMinutes, currentPhaseIndex, cycleLength, loop);
        // With its button, built by the app where it can (a phone that rings its reminders); elsewhere as it is.
        return new NotificationMessage(Kind, title, body, data, ShownByApp: cycleDone is not null);
    }

    /// <summary>
    /// The cycle just done, when a looping run's current phase is the first of the next cycle; else null. A session goes
    /// on by itself: its notice asks whether the person is done (the owner's choice, 2026-10-31).
    /// </summary>
    internal static int? CycleDone(PomodoroRunStatus status, int currentPhaseIndex, int cycleLength, bool loop) =>
        loop && status == PomodoroRunStatus.Active && cycleLength > 0 && currentPhaseIndex > 0 && currentPhaseIndex % cycleLength == 0
            ? currentPhaseIndex / cycleLength
            : null;

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
        if (CycleDone(status, currentPhaseIndex, cycleLength, loop) is { } done)
            return string.Format(
                currentType == PomodoroPhaseType.Break ? texts.CycleBeganBreakFormat : texts.CycleBeganFocusFormat,
                done,
                currentDurationMinutes
            );
        var lap = cycleLength > 0 ? currentPhaseIndex / cycleLength + 1 : 1;
        var lapPrefix = loop && lap > 1 ? string.Format(texts.LapFormat, lap) : "";
        return status == PomodoroRunStatus.Completed
            ? texts.Complete
            : currentType == PomodoroPhaseType.Break
                ? lapPrefix + string.Format(texts.BreakFormat, currentDurationMinutes)
                : lapPrefix + string.Format(texts.FocusFormat, currentDurationMinutes);
    }
}
