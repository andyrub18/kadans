using Kadans.SharedKernel.Errors;
using OneOf;

namespace Kadans.Api.Models;

public enum TaskStatus
{
    Scheduled,
    Started,
    Completed,
    Cancelled,
}

public sealed class TodoRemark
{
    public DateTimeOffset CreatedAt { get; init; } = DateTimeOffset.UtcNow;
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.UtcNow;
    public string Remark { get; set; } = string.Empty;
}

public sealed class Todo
{
    public Guid Id { get; init; } = Guid.CreateVersion7();
    public string Title { get; set; } = string.Empty;
    public string Description { get; set; } = string.Empty;
    public RecurrenceRule RecurrenceRule { get; set; } = null!;
    public bool NotificationEnabled { get; set; }
    public TimeSpan NotificationLeadTime { get; set; } = TimeSpan.FromMinutes(15);
    public List<TodoRemark> Remarks { get; set; } = [];
    public string UserId { get; set; } = string.Empty;
    public Guid? PomodoroTemplateId { get; set; }
    public PomodoroTemplate? PomodoroTemplate { get; set; }
    public TaskStatus Status { get; private set; } = TaskStatus.Scheduled;
    public DateTimeOffset CreatedAt { get; init; } = DateTimeOffset.UtcNow;
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.UtcNow;

    private Todo() { }

    public Todo(
        string title,
        string description,
        RecurrenceRule recurrenceRule,
        bool notificationEnabled = false
    )
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

    public OneOf<ApplicationError, Todo> RescheduleNextOccurrence(DateTimeOffset date)
    {
        var nextOccurrence = RecurrenceRule.GetNextOccurrence();
        if (nextOccurrence is null)
        {
            return new ApplicationError(
                ErrorTypes.NoNextOccurrenceFound,
                "No next occurrence found for the given recurrence rule."
            );
        }

        var rule = RecurrenceRule.CreateOneTimeRule(date);
        if (rule.IsT0)
            return rule.AsT0;

        RecurrenceRule.AddException(nextOccurrence.Value);

        return new Todo(Title, Description, rule.AsT1);
    }
}
