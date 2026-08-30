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

internal sealed class PomodoroRun
{
    public Guid Id { get; init; } = Guid.CreateVersion7();
    public Guid TodoId { get; set; }
    public Todo Todo { get; set; } = null!;
    public Guid? PomodoroTemplateId { get; set; }
    public string UserId { get; set; } = string.Empty;
    public PomodoroRunStatus Status { get; private set; } = PomodoroRunStatus.Active;
    public int CurrentPhaseIndex { get; set; }
    public List<PomodoroRunPhase> Phases { get; set; } = [];
    public DateTimeOffset StartedAt { get; init; } = DateTimeOffset.UtcNow;
    public DateTimeOffset? PausedAt { get; private set; }
    public DateTimeOffset? CompletedAt { get; private set; }
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.UtcNow;

    public void Pause()
    {
        Status = PomodoroRunStatus.Paused;
        PausedAt = DateTimeOffset.UtcNow;
        UpdatedAt = DateTimeOffset.UtcNow;
    }

    public void Resume()
    {
        Status = PomodoroRunStatus.Active;
        PausedAt = null;
        UpdatedAt = DateTimeOffset.UtcNow;
    }

    public void Complete()
    {
        Status = PomodoroRunStatus.Completed;
        CompletedAt = DateTimeOffset.UtcNow;
        UpdatedAt = DateTimeOffset.UtcNow;
    }

    public void Cancel()
    {
        Status = PomodoroRunStatus.Cancelled;
        CompletedAt = DateTimeOffset.UtcNow;
        UpdatedAt = DateTimeOffset.UtcNow;
    }
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
