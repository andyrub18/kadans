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

public sealed record PomodoroRunResponse(
    Guid Id,
    Guid TodoId,
    Guid? PomodoroTemplateId,
    PomodoroRunStatus Status,
    int CurrentPhaseIndex,
    IReadOnlyList<PomodoroRunPhaseResponse> Phases,
    DateTimeOffset StartedAt,
    DateTimeOffset? PausedAt,
    DateTimeOffset? CompletedAt,
    DateTimeOffset UpdatedAt
);
