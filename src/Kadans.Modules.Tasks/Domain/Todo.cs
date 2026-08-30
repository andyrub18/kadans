using Kadans.SharedKernel.Recurrence;

namespace Kadans.Modules.Tasks.Domain;

public enum TaskStatus
{
    Scheduled,
    Started,
    Completed,
    Cancelled,
}

internal sealed class TodoRemark
{
    public DateTimeOffset CreatedAt { get; init; } = DateTimeOffset.UtcNow;
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.UtcNow;
    public string Remark { get; set; } = string.Empty;
}

internal sealed class Todo
{
    public Guid Id { get; init; } = Guid.CreateVersion7();
    public string Title { get; set; } = string.Empty;
    public string Description { get; set; } = string.Empty;
    public RecurrenceRule RecurrenceRule { get; private set; } = null!;
    public bool NotificationEnabled { get; set; }
    public TimeSpan NotificationLeadTime { get; set; } = TimeSpan.FromMinutes(15);
    public List<TodoRemark> Remarks { get; set; } = [];
    public string UserId { get; set; } = string.Empty;
    public Guid? PomodoroTemplateId { get; set; }
    public PomodoroTemplate? PomodoroTemplate { get; set; }
    public TaskStatus Status { get; private set; } = TaskStatus.Scheduled;

    /// <summary>
    /// Occurrence rows exist for every rule instance up to this instant. <c>null</c> = nothing
    /// generated yet; <see cref="DateTimeOffset.MaxValue"/> = the rule is bounded and fully
    /// materialized, so the horizon job never needs to look at this todo again.
    /// </summary>
    public DateTimeOffset? OccurrencesGeneratedThrough { get; set; }

    public DateTimeOffset CreatedAt { get; init; } = DateTimeOffset.UtcNow;
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.UtcNow;

    public bool IsActive => Status is TaskStatus.Scheduled or TaskStatus.Started;
    public bool IsFullyGenerated => OccurrencesGeneratedThrough == DateTimeOffset.MaxValue;

    private Todo() { }

    public Todo(string title, string description, RecurrenceRule recurrenceRule, bool notificationEnabled = false)
    {
        Title = title;
        Description = description;
        RecurrenceRule = recurrenceRule;
        NotificationEnabled = notificationEnabled;
    }

    public void AddRemark(string remark)
    {
        Remarks.Add(new() { CreatedAt = DateTimeOffset.UtcNow, Remark = remark });
        UpdatedAt = DateTimeOffset.UtcNow;
    }

    public void UpdateStatus(TaskStatus status)
    {
        Status = status;
        UpdatedAt = DateTimeOffset.UtcNow;
    }

    /// <summary>Swaps the rule; occurrences must be regenerated afterwards.</summary>
    public void ReplaceRule(RecurrenceRule rule)
    {
        RecurrenceRule = rule;
        OccurrencesGeneratedThrough = null;
        UpdatedAt = DateTimeOffset.UtcNow;
    }
}
