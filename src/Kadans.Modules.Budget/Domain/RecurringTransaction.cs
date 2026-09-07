using System.ComponentModel.DataAnnotations.Schema;
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

    /// <summary>False once the rule is exhausted (count/until reached) or the user paused it.</summary>
    public bool IsActive { get; set; } = true;

    public DateTimeOffset CreatedAt { get; init; } = DateTimeOffset.UtcNow;
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.UtcNow;

    private RecurrenceSchedule? schedule;

    [NotMapped]
    public RecurrenceSchedule Schedule =>
        schedule ??= RecurrenceSchedule.FromStored(Rrule, TimeZoneId, StartDate, []);

    /// <summary>
    /// The occurrence instants that became due since the last run. Pure: the caller turns them
    /// into transactions and advances <see cref="GeneratedThrough"/>. Capped so a rule created
    /// far in the past cannot flood a single run.
    /// </summary>
    public IReadOnlyList<DateTimeOffset> DueOccurrences(DateTimeOffset now, int cap = 100)
    {
        var from = GeneratedThrough ?? StartDate.AddTicks(-1);
        if (from >= now)
            return [];
        return [.. Schedule.GetOccurrences(from, now).Where(o => o > from && o <= now).Take(cap)];
    }

    /// <summary>Exhausted rules deactivate so the job stops scanning them.</summary>
    public bool IsExhaustedAfter(DateTimeOffset now) => Schedule.GetNextOccurrence(now) is null;
}
