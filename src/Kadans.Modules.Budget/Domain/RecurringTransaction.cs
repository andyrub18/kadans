using System.ComponentModel.DataAnnotations.Schema;
using Kadans.SharedKernel.Errors;
using Kadans.SharedKernel.Recurrence;

namespace Kadans.Modules.Budget.Domain;

/// <summary>
/// A rule like "salary on the 1st" or "rent monthly": a transaction template plus a schedule on
/// the shared recurrence engine. A job materializes real transactions as their moments pass —
/// no client needs to be running (same philosophy as todo occurrences).
/// </summary>
internal sealed class RecurringTransaction
{
    public Guid Id { get; init; } = Guid.CreateVersion7();
    public required string UserId { get; init; }
    public required Guid AccountId { get; init; }
    public Account? Account { get; init; }
    public required TransactionKind Kind { get; init; }
    public required decimal Amount { get; set; }
    public required Currency Currency { get; init; }
    public Guid? CategoryId { get; set; }
    public Category? Category { get; set; }
    public string Note { get; set; } = "";

    public required string Rrule { get; init; }
    public required string TimeZoneId { get; init; }
    public required DateTimeOffset StartDate { get; init; }

    /// <summary>Occurrences up to here have been turned into transactions.</summary>
    public DateTimeOffset? GeneratedThrough { get; set; }

    /// <summary>
    /// When the next instance falls (after <see cref="GeneratedThrough"/>): what the job selects by, so a rule is
    /// touched when it has something to book, not on every pass. Null when exhausted or paused, or not computed yet
    /// (rules from before it was stored; the job computes it on their first pass).
    /// </summary>
    public DateTimeOffset? NextOccurrenceAt { get; set; }

    /// <summary>False once the rule is exhausted (count/until reached) or the user paused it.</summary>
    public bool IsActive { get; set; } = true;

    public DateTimeOffset CreatedAt { get; init; } = DateTimeOffset.UtcNow;
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.UtcNow;

    private RecurrenceSchedule? schedule;

    [NotMapped]
    public RecurrenceSchedule Schedule =>
        schedule ??= RecurrenceSchedule.FromStored(Rrule, TimeZoneId, StartDate, []);

    /// <summary>Most transactions one pass creates for a rule; a longer backlog continues on the next pass.</summary>
    public const int DueCap = 100;

    /// <summary>
    /// Money moves by the day: a recurring transaction repeats daily at most. And a rule backdated further than
    /// a year would replay more history than any budget screen shows.
    /// </summary>
    public static ApplicationError? CheckNewSchedule(Frequency frequency, DateTimeOffset start, DateTimeOffset now) =>
        frequency is Frequency.Minutely or Frequency.Hourly
            ? new ApplicationError(ErrorTypes.InvalidFrequency, "A recurring transaction can repeat at most once a day.")
            : start < now.AddYears(-1)
                ? new ApplicationError(ErrorTypes.InvalidStartDate, "A recurring transaction can start at most a year ago.")
                : null;

    /// <summary>
    /// The occurrence instants that became due since the last run, at most <see cref="DueCap"/>. Pure: the caller
    /// turns them into transactions, then calls <see cref="Advance"/>.
    /// </summary>
    public IReadOnlyList<DateTimeOffset> DueOccurrences(DateTimeOffset now)
    {
        var from = GeneratedThrough ?? StartDate.AddTicks(-1);
        if (from >= now)
            return [];
        // One more than kept: the instance at GeneratedThrough itself comes back first and is dropped.
        return [.. Schedule.GetOccurrences(from, now, limit: DueCap + 1).Where(o => o > from).Take(DueCap)];
    }

    /// <summary>
    /// After a pass turned <paramref name="materialized"/> into transactions. When the cap cut a backlog short (a
    /// rule backdated months), the next pass resumes after the last one; otherwise the rule is caught up to
    /// <paramref name="now"/>. A rule with nothing left after that point deactivates, so the job stops scanning it.
    /// </summary>
    public void Advance(IReadOnlyList<DateTimeOffset> materialized, DateTimeOffset now)
    {
        GeneratedThrough = materialized.Count == DueCap ? materialized[^1] : now;
        if (IsExhaustedAfter(GeneratedThrough.Value))
            IsActive = false;
        ScheduleNext();
    }

    /// <summary>After a change to the rule, its booking or its pause: when the job must look at it next.</summary>
    public void ScheduleNext() =>
        NextOccurrenceAt = IsActive ? Schedule.GetNextOccurrence(GeneratedThrough ?? StartDate.AddTicks(-1)) : null;

    public bool IsExhaustedAfter(DateTimeOffset at) => Schedule.GetNextOccurrence(at) is null;
}
