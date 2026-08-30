using Kadans.SharedKernel.Recurrence;

namespace Kadans.Modules.Tasks.Contracts;

public sealed record CreateOneTimeTodo(
    string Title,
    string Description,
    bool NotificationEnabled,
    DateTimeOffset DueDate,
    uint NotifyBeforeInMinutes = 15,
    Guid? PomodoroTemplateId = null
);

public sealed record CreateRecurringTodo(
    string Title,
    string Description,
    bool NotificationEnabled,
    CreateRecurrenceRule RecurrenceRule,
    uint NotifyBeforeInMinutes = 15,
    Guid? PomodoroTemplateId = null
);

public sealed record CreateRecurrenceRule(
    Frequency Frequency,
    DateTimeOffset StartDate,
    int Interval = 1,
    List<int>? ByHour = null,
    List<int>? ByMinute = null,
    List<DayOfWeek>? ByDayOfWeek = null,
    List<int>? ByMonthDay = null,
    List<int>? BySetPos = null,
    List<int>? ByMonth = null,
    int? Count = null,
    DateTimeOffset? Until = null,
    List<DateTimeOffset>? Exceptions = null,
    string? TimeZone = null
);
