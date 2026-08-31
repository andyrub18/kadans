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

    public DateTimeOffset StartedAt { get; private init; } = DateTimeOffset.UtcNow;
    public DateTimeOffset? PausedAt { get; private set; }
    public DateTimeOffset? CompletedAt { get; private set; }
    public DateTimeOffset UpdatedAt { get; private set; } = DateTimeOffset.UtcNow;

    public bool IsRunning => Status is PomodoroRunStatus.Active or PomodoroRunStatus.Paused;
    public PomodoroRunPhase CurrentPhase => Phases.OrderBy(p => p.Order).ElementAt(CurrentPhaseIndex);

    private PomodoroRun() { }

    public static PomodoroRun Start(
        Todo todo,
        IReadOnlyList<PomodoroTemplatePhase> templatePhases,
        string userId,
        bool autoAdvance,
        DateTimeOffset now
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

        var ordered = Phases.OrderBy(p => p.Order).ToList();
        var current = ordered[CurrentPhaseIndex];
        current.StartedAt ??= now;
        current.CompletedAt = now;

        if (CurrentPhaseIndex == ordered.Count - 1)
        {
            Status = PomodoroRunStatus.Completed;
            CompletedAt = now;
            PhaseEndsAt = null;
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
}
