using Kadans.SharedKernel.Recurrence;

namespace Kadans.Modules.Tasks.Features.Todos.Occurrences;

/// <summary>
/// Pure planning of which rule instances to materialize next. Kept free of persistence so the
/// horizon/batch/exhaustion rules are unit-testable.
/// </summary>
internal static class OccurrencePlanner
{
    internal sealed record Plan(IReadOnlyList<DateTimeOffset> ToInsert, DateTimeOffset GeneratedThrough);

    /// <param name="generatedThrough">Where the previous pass stopped; null on the first pass.</param>
    /// <param name="horizon">Materialize instances up to and including this instant.</param>
    /// <param name="existingOriginals">Instances already stored, to keep regeneration idempotent.</param>
    /// <param name="maxBatch">Cap per pass; when hit, <see cref="Plan.GeneratedThrough"/> is the last instance taken.</param>
    public static Plan Next(
        RecurrenceSchedule schedule,
        DateTimeOffset? generatedThrough,
        DateTimeOffset horizon,
        IReadOnlySet<DateTimeOffset> existingOriginals,
        int maxBatch
    )
    {
        var from = generatedThrough ?? schedule.Start;
        var taken = new List<DateTimeOffset>();
        var truncated = false;

        foreach (var instance in schedule.GetOccurrences(from, horizon))
        {
            // Continue strictly after the previous pass.
            if (generatedThrough is not null && instance <= generatedThrough.Value)
                continue;

            if (taken.Count >= maxBatch)
            {
                truncated = true;
                break;
            }

            taken.Add(instance);
        }

        var toInsert = taken.Where(instance => !existingOriginals.Contains(instance)).ToList();

        DateTimeOffset newGeneratedThrough;
        if (truncated)
            newGeneratedThrough = taken[^1];
        else if (IsExhaustedBy(schedule, horizon))
            newGeneratedThrough = DateTimeOffset.MaxValue;
        else
            newGeneratedThrough = horizon;

        return new Plan(toInsert, newGeneratedThrough);
    }

    /// <summary>A bounded rule whose last instance is within the horizon has nothing left to generate.</summary>
    private static bool IsExhaustedBy(RecurrenceSchedule schedule, DateTimeOffset horizon)
    {
        if (schedule.IsIndefinite)
            return false;

        var last = schedule.GetLastOccurrence();
        return last is null || last.Value <= horizon;
    }
}
