using Kadans.Modules.Tasks.Domain;

namespace Kadans.Modules.Tasks.Contracts;

public sealed record PomodoroPhaseResponse(
    Guid Id,
    int Order,
    PomodoroPhaseType Type,
    int DurationMinutes
);

public sealed record PomodoroTemplateResponse(
    Guid Id,
    string Name,
    IReadOnlyList<PomodoroPhaseResponse> Phases,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt
);

public sealed record PomodoroRunPhaseResponse(
    Guid Id,
    int Order,
    PomodoroPhaseType Type,
    int DurationMinutes,
    DateTimeOffset? StartedAt,
    DateTimeOffset? CompletedAt
);

/// <summary>
/// While active, clients count down to <see cref="PhaseEndsAt"/>; while paused,
/// <see cref="PausedRemainingSeconds"/> is what is left of the current phase.
/// </summary>
public sealed record PomodoroRunResponse(
    Guid Id,
    Guid TodoId,
    Guid? PomodoroTemplateId,
    PomodoroRunStatus Status,
    int CurrentPhaseIndex,
    DateTimeOffset? PhaseEndsAt,
    int? PausedRemainingSeconds,
    bool AutoAdvance,
    bool Loop,
    int CycleLength,
    IReadOnlyList<PomodoroRunPhaseResponse> Phases,
    DateTimeOffset StartedAt,
    DateTimeOffset? PausedAt,
    DateTimeOffset? CompletedAt,
    DateTimeOffset UpdatedAt,
    /// <summary>When the session ends by itself unless finished sooner: its end time, or the next occurrence's start.</summary>
    DateTimeOffset? FinishAt = null,
    /// <summary>The occurrence the session is for: its end offers to mark it done. Null when none was near its start.</summary>
    Guid? OccurrenceId = null,
    /// <summary>A hands-free lap ran out and the session waits for the person: "Another cycle?" resumes it.</summary>
    bool BetweenLaps = false,
    /// <summary><see cref="FinishAt"/> is when the todo's next occurrence starts, sooner than the session's own end time.</summary>
    bool EndsWithNextOccurrence = false
);

public sealed record PomodoroDayStats(DateOnly Date, int FocusMinutes, int BreakMinutes, int CompletedRuns);

public sealed record PomodoroStatsResponse(
    DateTimeOffset From,
    DateTimeOffset To,
    string TimeZoneId,
    int CompletedRuns,
    int CancelledRuns,
    int FocusMinutes,
    int BreakMinutes,
    IReadOnlyList<PomodoroDayStats> PerDay
);
