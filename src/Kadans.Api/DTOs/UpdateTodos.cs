using TaskStatus = Kadans.Api.Models.TaskStatus;

namespace Kadans.Api.DTOs;

public sealed record UpdateTodo(
    string Title,
    string Description,
    CreateRecurrenceRule RecurrenceRule,
    bool NotificationEnabled,
    TaskStatus Status,
    Guid? PomodoroTemplateId = null
);

public sealed record RescheduleNextOccurrence(DateTimeOffset NewDate, string? Reason = null);

public sealed record Cancel(string Reason = "");

public sealed record AddRemark(string Remark);

public sealed record UpdateTodoPomodoro(Guid? PomodoroTemplateId);
