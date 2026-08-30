using Kadans.Modules.Tasks.Features.Todos.Occurrences;
using Kadans.SharedKernel.Recurrence;

namespace Kadans.Tasks.Tests;

public class OccurrencePlannerTests
{
    private static DateTimeOffset Utc(int y, int m, int d, int h = 0) => new(y, m, d, h, 0, 0, TimeSpan.Zero);

    private static RecurrenceSchedule Daily(DateTimeOffset start, int? count = null) =>
        RecurrenceSchedule.Create(new RecurrenceSpec(Frequency.Daily, Count: count), start).AsT1;

    [Test]
    public async Task First_pass_materializes_from_the_rule_start_to_the_horizon()
    {
        var plan = OccurrencePlanner.Next(Daily(Utc(2027, 1, 1, 9)), generatedThrough: null, horizon: Utc(2027, 1, 4), new HashSet<DateTimeOffset>(), maxBatch: 100);

        await Assert.That(plan.ToInsert).IsEquivalentTo([Utc(2027, 1, 1, 9), Utc(2027, 1, 2, 9), Utc(2027, 1, 3, 9)]);
        await Assert.That(plan.GeneratedThrough).IsEqualTo(Utc(2027, 1, 4));
    }

    [Test]
    public async Task Next_pass_continues_strictly_after_the_previous_marker()
    {
        var plan = OccurrencePlanner.Next(Daily(Utc(2027, 1, 1, 9)), generatedThrough: Utc(2027, 1, 4), horizon: Utc(2027, 1, 6), new HashSet<DateTimeOffset>(), maxBatch: 100);

        await Assert.That(plan.ToInsert).IsEquivalentTo([Utc(2027, 1, 4, 9), Utc(2027, 1, 5, 9)]);
    }

    [Test]
    public async Task Marker_that_is_an_instance_is_not_generated_twice()
    {
        // A truncated pass leaves the marker exactly on the last instance taken.
        var plan = OccurrencePlanner.Next(Daily(Utc(2027, 1, 1, 9)), generatedThrough: Utc(2027, 1, 2, 9), horizon: Utc(2027, 1, 4), new HashSet<DateTimeOffset>(), maxBatch: 100);

        await Assert.That(plan.ToInsert).IsEquivalentTo([Utc(2027, 1, 3, 9)]);
    }

    [Test]
    public async Task Batch_cap_stops_at_the_last_instance_taken()
    {
        var plan = OccurrencePlanner.Next(Daily(Utc(2027, 1, 1, 9)), null, horizon: Utc(2027, 2, 1), new HashSet<DateTimeOffset>(), maxBatch: 5);

        await Assert.That(plan.ToInsert.Count).IsEqualTo(5);
        await Assert.That(plan.GeneratedThrough).IsEqualTo(Utc(2027, 1, 5, 9));
    }

    [Test]
    public async Task Bounded_rule_inside_the_horizon_is_marked_exhausted()
    {
        var plan = OccurrencePlanner.Next(Daily(Utc(2027, 1, 1, 9), count: 3), null, horizon: Utc(2027, 2, 1), new HashSet<DateTimeOffset>(), maxBatch: 100);

        await Assert.That(plan.ToInsert.Count).IsEqualTo(3);
        await Assert.That(plan.GeneratedThrough).IsEqualTo(DateTimeOffset.MaxValue);
    }

    [Test]
    public async Task Bounded_rule_beyond_the_horizon_is_not_exhausted_yet()
    {
        var plan = OccurrencePlanner.Next(Daily(Utc(2027, 1, 1, 9), count: 60), null, horizon: Utc(2027, 1, 10), new HashSet<DateTimeOffset>(), maxBatch: 100);

        await Assert.That(plan.ToInsert.Count).IsEqualTo(9);
        await Assert.That(plan.GeneratedThrough).IsEqualTo(Utc(2027, 1, 10));
    }

    [Test]
    public async Task Existing_instances_are_skipped_but_still_advance_the_marker()
    {
        var existing = new HashSet<DateTimeOffset> { Utc(2027, 1, 2, 9) };
        var plan = OccurrencePlanner.Next(Daily(Utc(2027, 1, 1, 9)), null, horizon: Utc(2027, 1, 4), existing, maxBatch: 100);

        await Assert.That(plan.ToInsert).IsEquivalentTo([Utc(2027, 1, 1, 9), Utc(2027, 1, 3, 9)]);
        await Assert.That(plan.GeneratedThrough).IsEqualTo(Utc(2027, 1, 4));
    }

    [Test]
    public async Task One_time_rule_yields_one_instance_and_is_exhausted()
    {
        var schedule = RecurrenceSchedule.OneTime(Utc(2027, 3, 1, 15)).AsT1;
        var plan = OccurrencePlanner.Next(schedule, null, horizon: Utc(2027, 4, 1), new HashSet<DateTimeOffset>(), maxBatch: 100);

        await Assert.That(plan.ToInsert).IsEquivalentTo([Utc(2027, 3, 1, 15)]);
        await Assert.That(plan.GeneratedThrough).IsEqualTo(DateTimeOffset.MaxValue);
    }
}
