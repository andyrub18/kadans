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

/// <summary>A note on an occurrence ("how it went"), at most 4,000 characters; blank or null removes it.</summary>
public sealed record SetOccurrenceRemark(string? Remark);

public sealed record UpdateTodoPomodoro(Guid? PomodoroTemplateId);
