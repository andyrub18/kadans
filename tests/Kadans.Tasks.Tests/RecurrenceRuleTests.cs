using Kadans.Modules.Tasks.Domain;
using Kadans.SharedKernel.Recurrence;

namespace Kadans.Tasks.Tests;

public class RecurrenceRuleTests
{
    // Rules refuse start dates in the past, so anchor every test one year out.
    private static readonly DateTimeOffset Start = new(
        DateTimeOffset.UtcNow.Year + 1, 1, 1, 9, 0, 0, TimeSpan.Zero
    );

    [Test]
    public async Task Daily_rule_yields_one_occurrence_per_day()
    {
        var rule = RecurrenceRule.Create(Frequency.Daily, Start);

        await Assert.That(rule.IsT1).IsTrue();

        var occurrences = rule.AsT1.GetOccurrences(Start, Start.AddDays(6));

        await Assert.That(occurrences.Count).IsEqualTo(7);
        await Assert.That(occurrences[0]).IsEqualTo(Start);
    }

    [Test]
    public async Task One_time_rule_yields_exactly_one_occurrence()
    {
        var rule = RecurrenceRule.CreateOneTimeRule(Start);

        await Assert.That(rule.IsT1).IsTrue();
        await Assert.That(rule.AsT1.IsOneTime).IsTrue();

        var occurrences = rule.AsT1.GetOccurrences(Start.AddYears(-1), Start.AddYears(1));

        await Assert.That(occurrences.Count).IsEqualTo(1);
    }

    [Test]
    public async Task Rule_rejects_count_and_until_together()
    {
        var rule = RecurrenceRule.Create(
            Frequency.Weekly,
            Start,
            count: 3,
            until: Start.AddMonths(1)
        );

        await Assert.That(rule.IsT0).IsTrue();
    }

    [Test]
    public async Task What_is_left_of_a_counted_rule_is_read_from_the_rule()
    {
        // Ten days from Start at 09:00: by Start + 3 days at noon, four have come.
        var rule = RecurrenceRule.Create(Frequency.Daily, Start, count: 10).AsT1;

        await Assert.That(rule.RemainingAfter(Start.AddDays(-1))).IsEqualTo(10);
        await Assert.That(rule.RemainingAfter(Start.AddDays(3).AddHours(3))).IsEqualTo(6);
        await Assert.That(rule.RemainingAfter(Start.AddDays(30))).IsEqualTo(0);
        await Assert.That(RecurrenceRule.Create(Frequency.Daily, Start).AsT1.RemainingAfter(Start.AddDays(3))).IsNull();
    }

    [Test]
    public async Task An_exception_counts_as_a_time_that_came()
    {
        var rule = RecurrenceRule.Create(Frequency.Daily, Start, count: 10).AsT1;
        rule.AddException(Start.AddDays(1));

        // Days 0, 2 and 3 came, day 1 was skipped: still four of the ten used, as COUNT counts before exceptions.
        await Assert.That(rule.RemainingAfter(Start.AddDays(3).AddHours(3))).IsEqualTo(6);
    }
}
