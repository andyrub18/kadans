namespace Kadans.Modules.Tasks.Contracts;

/// <summary>Leave <see cref="RecurrenceRule"/> null to keep the current rule (and its occurrences).</summary>
public sealed record UpdateTodo(
    string Title,
    string Description,
    bool NotificationEnabled,
    Guid? PomodoroTemplateId = null,
    CreateRecurrenceRule? RecurrenceRule = null,
    uint? NotifyBeforeInMinutes = null
);

public sealed record RescheduleOccurrence(DateTimeOffset NewDate, string? Reason = null);

public sealed record Cancel(string Reason = "");

public sealed record AddRemark(string Remark);

public sealed record ReplaceRemarks(List<string> Remarks);

public sealed record UpdateTodoPomodoro(Guid? PomodoroTemplateId);
