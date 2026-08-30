namespace Kadans.Api.DTOs;

public sealed record UpdateTodo(
    string Title,
    string Description,
    CreateRecurrenceRule RecurrenceRule,
    bool NotificationEnabled,
    Guid? PomodoroTemplateId = null
);

public sealed record RescheduleNextOccurrence(DateTimeOffset NewDate, string? Reason = null);

public sealed record Cancel(string Reason = "");

public sealed record AddRemark(string Remark);

public sealed record ReplaceRemarks(List<string> Remarks);

public sealed record UpdateTodoPomodoro(Guid? PomodoroTemplateId);
