using Kadans.SharedKernel.Errors;
using OneOf;
using OneOf.Types;

namespace Kadans.Modules.Tasks.Domain;

public enum OccurrenceStatus
{
    Pending,
    Completed,
    Cancelled,
}

/// <summary>
/// One materialized instance of a todo's rule. <see cref="OriginalScheduledAt"/> is the instant
/// the rule produced (the identity of the instance, unique per todo); <see cref="ScheduledAt"/>
/// is when it actually happens after any reschedule. Overrides live here, never on the rule.
/// </summary>
internal sealed class TodoOccurrence
{
    public Guid Id { get; init; } = Guid.CreateVersion7();
    public Guid TodoId { get; init; }
    public Todo? Todo { get; init; }

    public DateTimeOffset OriginalScheduledAt { get; init; }
    public DateTimeOffset ScheduledAt { get; set; }
    public OccurrenceStatus Status { get; private set; } = OccurrenceStatus.Pending;

    public DateTimeOffset? RescheduledAt { get; private set; }
    public string? RescheduleReason { get; private set; }
    public DateTimeOffset? CompletedAt { get; private set; }
    public DateTimeOffset? CancelledAt { get; private set; }
    public string? CancellationReason { get; private set; }
    public string? Remarks { get; set; }

    /// <summary>When the reminder is due (<c>ScheduledAt - lead time</c>); null when notifications are off.</summary>
    public DateTimeOffset? NotifyAt { get; private set; }

    /// <summary>Stamped by the reminder job so a reminder is sent once.</summary>
    public DateTimeOffset? NotifiedAt { get; set; }

    public bool IsPending => Status == OccurrenceStatus.Pending;
    public bool IsRescheduled => RescheduledAt is not null;

    /// <summary>True when the user never touched this row, so a rule change may drop it silently.</summary>
    public bool IsUntouched => IsPending && !IsRescheduled && Remarks is null;

    public OneOf<ApplicationError, Success> Complete(DateTimeOffset now)
    {
        if (NotPending() is { } error)
            return error;

        Status = OccurrenceStatus.Completed;
        CompletedAt = now;
        return new Success();
    }

    public OneOf<ApplicationError, Success> Cancel(string? reason, DateTimeOffset now)
    {
        if (NotPending() is { } error)
            return error;

        Status = OccurrenceStatus.Cancelled;
        CancelledAt = now;
        CancellationReason = string.IsNullOrWhiteSpace(reason) ? null : reason;
        return new Success();
    }

    public OneOf<ApplicationError, Success> Reschedule(DateTimeOffset newDate, string? reason, DateTimeOffset now)
    {
        if (NotPending() is { } error)
            return error;

        if (newDate <= now)
            return new ApplicationError(ErrorTypes.InvalidDueDate, "The new date must be in the future.");

        ScheduledAt = newDate;
        RescheduledAt = now;
        RescheduleReason = string.IsNullOrWhiteSpace(reason) ? null : reason;
        // A moved occurrence deserves a fresh reminder.
        NotifiedAt = null;
        if (Todo is not null)
            RefreshNotifyAt(Todo);
        return new Success();
    }

    public void RefreshNotifyAt(Todo todo) =>
        NotifyAt = todo.NotificationEnabled ? ScheduledAt - todo.NotificationLeadTime : null;

    private ApplicationError? NotPending() =>
        Status switch
        {
            OccurrenceStatus.Completed => new ApplicationError(ErrorTypes.TaskAlreadyCompleted, $"Occurrence {Id} is already completed."),
            OccurrenceStatus.Cancelled => new ApplicationError(ErrorTypes.TaskAlreadyCancelled, $"Occurrence {Id} is already cancelled."),
            _ => null,
        };
}
