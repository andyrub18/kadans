using Kadans.Api.Models;
using Kadans.SharedKernel.Recurrence;

namespace Kadans.Api.Tests;

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
}
