using Kadans.SharedKernel.Errors;
using OneOf;
using OneOf.Types;

namespace Kadans.Modules.Tasks.Domain;

public enum PomodoroPhaseType
{
    Focus,
    Break,
}

public enum PomodoroRunStatus
{
    Active,
    Paused,
    Completed,
    Cancelled,
}

internal sealed class PomodoroTemplate
{
    /// <summary>A cycle holds at most this many phases: four focus/break rounds with a long break are 8.</summary>
    public const int MaxPhases = 24;

    /// <summary>A phase lasts 1 minute to 4 hours.</summary>
    public const int MaxPhaseMinutes = 240;

    public Guid Id { get; init; } = Guid.CreateVersion7();
    public string Name { get; set; } = string.Empty;
    public string UserId { get; set; } = string.Empty;
    public List<PomodoroTemplatePhase> Phases { get; set; } = [];
    public DateTimeOffset CreatedAt { get; init; } = DateTimeOffset.UtcNow;
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.UtcNow;
}

internal sealed class PomodoroTemplatePhase
{
    public Guid Id { get; init; } = Guid.CreateVersion7();
    public Guid PomodoroTemplateId { get; set; }
    public int Order { get; set; }
    public PomodoroPhaseType Type { get; set; }
    public int DurationMinutes { get; set; }
}

/// <summary>
/// A running pomodoro session. The server is the source of truth: while active, clients simply
/// count down to <see cref="PhaseEndsAt"/>; pausing freezes the remainder, resuming re-anchors
/// it. That makes the state trivially correct across devices and reconnects.
/// </summary>
internal sealed class PomodoroRun
{
    public Guid Id { get; init; } = Guid.CreateVersion7();
    public Guid TodoId { get; private init; }
    public Todo Todo { get; private init; } = null!;
    public Guid? PomodoroTemplateId { get; private init; }
    public string UserId { get; private init; } = string.Empty;
    public PomodoroRunStatus Status { get; private set; } = PomodoroRunStatus.Active;
    public int CurrentPhaseIndex { get; private set; }
    public List<PomodoroRunPhase> Phases { get; private set; } = [];

    /// <summary>When the current phase runs out; non-null only while <see cref="PomodoroRunStatus.Active"/>.</summary>
    public DateTimeOffset? PhaseEndsAt { get; private set; }

    /// <summary>What was left of the current phase when paused; non-null only while <see cref="PomodoroRunStatus.Paused"/>.</summary>
    public TimeSpan? PausedRemaining { get; private set; }

    /// <summary>When true, the server advances phases as they run out (and notifies); otherwise the client calls advance.</summary>
    public bool AutoAdvance { get; private set; }

    /// <summary>
    /// Manual runs: the last phase whose "time's up" was sent. A manual run waits at the end of each phase for the
    /// person to move on, and says so once per phase (pausing and resuming at 0:00 does not say it again).
    /// </summary>
    public int? TimeUpPhaseIndex { get; private set; }

    /// <summary>A pomodoro proper: after the last phase the cycle starts over (a new lap) until <see cref="Finish"/>.</summary>
    public bool Loop { get; private set; }

    /// <summary>Phases per lap, fixed at start. CurrentPhaseIndex / CycleLength is the lap number.</summary>
    public int CycleLength { get; private set; }

    /// <summary>
    /// When the session ends by itself, even if nobody presses Finish: a looping session left running overnight
    /// would otherwise notify all night. Chosen at start (or later), <see cref="DefaultSpan"/> after it by default.
    /// Null only for runs that ended before end times existed.
    /// </summary>
    public DateTimeOffset? FinishBy { get; private set; }

    /// <summary>A workday and then some.</summary>
    public static readonly TimeSpan DefaultSpan = TimeSpan.FromHours(12);

    /// <summary>The latest end a session can be given: a day from now.</summary>
    public static readonly TimeSpan MaxSpan = TimeSpan.FromHours(24);

    public DateTimeOffset StartedAt { get; private init; } = DateTimeOffset.UtcNow;
    public DateTimeOffset? PausedAt { get; private set; }
    public DateTimeOffset? CompletedAt { get; private set; }
    public DateTimeOffset UpdatedAt { get; private set; } = DateTimeOffset.UtcNow;

    public bool IsRunning => Status is PomodoroRunStatus.Active or PomodoroRunStatus.Paused;

    /// <summary>
    /// Whether a watching app's "this phase ran out" may advance the run: only a hands-free run, and only once the
    /// server's clock agrees. A device whose clock runs fast would otherwise cut every phase short.
    /// </summary>
    public bool DeadlineReached(DateTimeOffset now) =>
        Status == PomodoroRunStatus.Active && AutoAdvance && PhaseEndsAt is { } end && end <= now;

    /// <summary>A manual run whose current phase has run out and has not said so yet.</summary>
    public bool TimeUpDue(DateTimeOffset now) =>
        Status == PomodoroRunStatus.Active && !AutoAdvance && PhaseEndsAt is { } end && end <= now
        && (TimeUpPhaseIndex is null || TimeUpPhaseIndex < CurrentPhaseIndex);

    /// <summary>The current phase's "time's up" went out. Not a change the person sees: <see cref="UpdatedAt"/> stays.</summary>
    public void TimeUpSent() => TimeUpPhaseIndex = CurrentPhaseIndex;
    public PomodoroRunPhase CurrentPhase => Phases.OrderBy(p => p.Order).ElementAt(CurrentPhaseIndex);

    private PomodoroRun() { }

    /// <param name="finishBy">When the session ends by itself; null is <see cref="DefaultSpan"/> from now. See <see cref="CheckFinishBy"/>.</param>
    public static PomodoroRun Start(
        Todo todo,
        IReadOnlyList<PomodoroTemplatePhase> templatePhases,
        string userId,
        bool autoAdvance,
        DateTimeOffset now,
        bool loop = false,
        DateTimeOffset? finishBy = null
    )
    {
        var ordered = templatePhases.OrderBy(p => p.Order).ToList();
        var run = new PomodoroRun
        {
            TodoId = todo.Id,
            Todo = todo,
            PomodoroTemplateId = todo.PomodoroTemplateId,
            UserId = userId,
            AutoAdvance = autoAdvance,
            Loop = loop,
            CycleLength = ordered.Count,
            FinishBy = finishBy ?? now + DefaultSpan,
            StartedAt = now,
            UpdatedAt = now,
            Phases = ordered
                .Select((phase, index) => new PomodoroRunPhase
                {
                    Order = index,
                    Type = phase.Type,
                    DurationMinutes = phase.DurationMinutes,
                    StartedAt = index == 0 ? now : null,
                })
                .ToList(),
        };
        run.PhaseEndsAt = now + TimeSpan.FromMinutes(ordered[0].DurationMinutes);
        return run;
    }

    /// <summary>An end the person picked: at least a minute away, at most <see cref="MaxSpan"/>.</summary>
    public static ApplicationError? CheckFinishBy(DateTimeOffset finishBy, DateTimeOffset now) =>
        finishBy < now + TimeSpan.FromMinutes(1) || finishBy > now + MaxSpan
            ? new ApplicationError(ErrorTypes.ValidationError, "A session ends between 1 minute and 24 hours from now.")
            : null;

    /// <summary>Working later (or stopping sooner) than planned.</summary>
    public OneOf<ApplicationError, Success> ChangeFinishBy(DateTimeOffset finishBy, DateTimeOffset now)
    {
        if (!IsRunning)
            return InvalidState("Only active or paused runs can change their end.");
        if (CheckFinishBy(finishBy, now) is { } error)
            return error;

        FinishBy = finishBy;
        UpdatedAt = now;
        return new Success();
    }

    /// <summary>The end time has come and nobody pressed Finish.</summary>
    public bool FinishDue(DateTimeOffset now) => IsRunning && FinishBy is { } end && end <= now;

    public OneOf<ApplicationError, Success> Pause(DateTimeOffset now)
    {
        if (Status != PomodoroRunStatus.Active)
            return InvalidState("Only active runs can be paused.");

        var remaining = PhaseEndsAt!.Value - now;
        PausedRemaining = remaining > TimeSpan.Zero ? remaining : TimeSpan.Zero;
        PhaseEndsAt = null;
        PausedAt = now;
        Status = PomodoroRunStatus.Paused;
        UpdatedAt = now;
        return new Success();
    }

    public OneOf<ApplicationError, Success> Resume(DateTimeOffset now)
    {
        if (Status != PomodoroRunStatus.Paused)
            return InvalidState("Only paused runs can be resumed.");

        CurrentPhase.PausedSeconds += PausedSecondsUntil(now);
        PhaseEndsAt = now + PausedRemaining!.Value;
        PausedRemaining = null;
        PausedAt = null;
        Status = PomodoroRunStatus.Active;
        UpdatedAt = now;
        return new Success();
    }

    /// <summary>Finishes the current phase and starts the next one, or completes the run after the last.</summary>
    public OneOf<ApplicationError, Success> Advance(int? expectedPhaseIndex, DateTimeOffset now)
    {
        if (Status != PomodoroRunStatus.Active)
            return InvalidState("Only active runs can advance phases.");

        if (expectedPhaseIndex is not null && expectedPhaseIndex.Value != CurrentPhaseIndex)
            return InvalidState("Run phase index mismatch. Refresh run state and retry.");

        // Hands-free runs keep their cadence: an overdue phase completed when it ran out, not
        // when the advancing request (a watching client, or the catch-up job) happened to land.
        if (AutoAdvance && PhaseEndsAt is { } scheduledEnd && scheduledEnd < now)
            now = scheduledEnd;

        var ordered = Phases.OrderBy(p => p.Order).ToList();
        var current = ordered[CurrentPhaseIndex];
        current.StartedAt ??= now;
        current.CompletedAt = now;

        if (CurrentPhaseIndex == ordered.Count - 1)
        {
            if (Loop)
            {
                // New lap: append fresh copies of the first cycle so every lap keeps its own
                // timestamps (history and stats count each completed phase).
                var lapStart = ordered.Count;
                var lap = ordered
                    .Take(CycleLength)
                    .Select((template, offset) => new PomodoroRunPhase
                    {
                        Order = lapStart + offset,
                        Type = template.Type,
                        DurationMinutes = template.DurationMinutes,
                    })
                    .ToList();
                Phases.AddRange(lap);
                CurrentPhaseIndex = lapStart;
                lap[0].StartedAt = now;
                PhaseEndsAt = now + TimeSpan.FromMinutes(lap[0].DurationMinutes);
            }
            else
            {
                Status = PomodoroRunStatus.Completed;
                CompletedAt = now;
                PhaseEndsAt = null;
            }
        }
        else
        {
            CurrentPhaseIndex++;
            var next = ordered[CurrentPhaseIndex];
            next.StartedAt = now;
            PhaseEndsAt = now + TimeSpan.FromMinutes(next.DurationMinutes);
        }

        UpdatedAt = now;
        return new Success();
    }

    /// <summary>
    /// The workday is over: a looping session ends as completed, not cancelled. The phase under way ends here too,
    /// so the focus time it holds counts (a pause it ends in counts as paused).
    /// </summary>
    public OneOf<ApplicationError, Success> Finish(DateTimeOffset now)
    {
        if (!IsRunning)
            return InvalidState("Only active or paused runs can be finished.");

        var current = CurrentPhase;
        if (current.StartedAt is not null && current.CompletedAt is null)
        {
            if (Status == PomodoroRunStatus.Paused)
                current.PausedSeconds += PausedSecondsUntil(now);
            current.CompletedAt = now;
        }

        Status = PomodoroRunStatus.Completed;
        CompletedAt = now;
        PhaseEndsAt = null;
        PausedRemaining = null;
        UpdatedAt = now;
        return new Success();
    }

    public OneOf<ApplicationError, Success> Cancel(DateTimeOffset now)
    {
        if (!IsRunning)
            return InvalidState("Only active or paused runs can be cancelled.");

        Status = PomodoroRunStatus.Cancelled;
        CompletedAt = now;
        PhaseEndsAt = null;
        PausedRemaining = null;
        UpdatedAt = now;
        return new Success();
    }

    private int PausedSecondsUntil(DateTimeOffset now) =>
        PausedAt is { } since && now > since ? (int)Math.Round((now - since).TotalSeconds) : 0;

    private static ApplicationError InvalidState(string message) =>
        new(ErrorTypes.PomodoroRunInvalidState, message);
}

internal sealed class PomodoroRunPhase
{
    public Guid Id { get; init; } = Guid.CreateVersion7();
    public Guid PomodoroRunId { get; set; }
    public int Order { get; set; }
    public PomodoroPhaseType Type { get; set; }
    public int DurationMinutes { get; set; }
    public DateTimeOffset? StartedAt { get; set; }
    public DateTimeOffset? CompletedAt { get; set; }

    /// <summary>Time spent paused within this phase: what stats leave out.</summary>
    public int PausedSeconds { get; set; }

    /// <summary>
    /// The time this phase really took: skipped after 5 minutes it is 5, kept going past its timer it is longer.
    /// Null until it ends.
    /// </summary>
    public int? ActualSeconds => SecondsSpent(StartedAt, CompletedAt, PausedSeconds);

    internal static int? SecondsSpent(DateTimeOffset? startedAt, DateTimeOffset? completedAt, int pausedSeconds) =>
        startedAt is { } started && completedAt is { } completed
            ? Math.Max(0, (int)Math.Round((completed - started).TotalSeconds) - pausedSeconds)
            : null;
}
