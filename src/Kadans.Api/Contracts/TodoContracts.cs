using Kadans.SharedKernel.Recurrence;
using TaskStatus = Kadans.Api.Models.TaskStatus;

namespace Kadans.Api.Contracts;

public sealed record RecurrenceRuleResponse(
    string Rrule,
    string TimeZoneId,
    DateTimeOffset StartDate,
    Frequency Frequency,
    int Interval,
    int? Count,
    DateTimeOffset? Until,
    bool IsOneTime,
    IReadOnlyList<DateTimeOffset> Exceptions
);

public sealed record TodoRemarkResponse(
    string Remark,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt
);

public sealed record TodoResponse(
    Guid Id,
    string Title,
    string Description,
    TaskStatus Status,
    bool NotificationEnabled,
    int NotifyBeforeInMinutes,
    Guid? PomodoroTemplateId,
    RecurrenceRuleResponse? RecurrenceRule,
    IReadOnlyList<TodoRemarkResponse> Remarks,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt
);

public sealed record TodoOccurrenceResponse(
    Guid Id,
    Guid TodoId,
    string TodoTitle,
    DateTimeOffset OccurrenceDate,
    bool IsCompleted,
    DateTimeOffset? CompletedAt,
    bool IsCancelled,
    string CancellationReason,
    string Remarks
);
