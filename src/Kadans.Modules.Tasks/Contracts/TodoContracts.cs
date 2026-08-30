using Kadans.Modules.Tasks.Domain;
using Kadans.SharedKernel.Recurrence;
using TaskStatus = Kadans.Modules.Tasks.Domain.TaskStatus;

namespace Kadans.Modules.Tasks.Contracts;

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

/// <summary>
/// A materialized occurrence, or – when <see cref="IsPreview"/> is true – a computed instance beyond
/// the materialization horizon (no <see cref="Id"/>; it cannot be acted on until it materializes).
/// </summary>
public sealed record TodoOccurrenceResponse(
    Guid? Id,
    Guid TodoId,
    string TodoTitle,
    DateTimeOffset ScheduledAt,
    DateTimeOffset OriginalScheduledAt,
    OccurrenceStatus Status,
    bool IsRescheduled,
    string? RescheduleReason,
    DateTimeOffset? CompletedAt,
    DateTimeOffset? CancelledAt,
    string? CancellationReason,
    string? Remarks,
    bool IsPreview
);
