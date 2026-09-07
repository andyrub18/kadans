using Kadans.Modules.Budget.Domain;
using Kadans.SharedKernel.Recurrence;

namespace Kadans.Budget.Tests;

public class RecurringTransactionTests
{
    private static readonly DateTimeOffset Start = new(2026, 9, 1, 8, 0, 0, TimeSpan.Zero);

    private static RecurringTransaction MonthlySalary()
    {
        var schedule = RecurrenceSchedule.Create(
            new RecurrenceSpec(Frequency.Monthly, ByMonthDay: [1]),
            Start,
            "America/Port-au-Prince"
        ).AsT1;
        return new RecurringTransaction
        {
            UserId = "u1",
            AccountId = Guid.CreateVersion7(),
            Kind = TransactionKind.Income,
            Amount = 85_000m,
            Currency = Currency.Htg,
            Rrule = schedule.Rrule,
            TimeZoneId = schedule.TimeZoneId,
            StartDate = schedule.Start,
        };
    }

    [Test]
    public async Task A_fresh_rule_owes_everything_from_its_start()
    {
        var rule = MonthlySalary();
        var due = rule.DueOccurrences(new DateTimeOffset(2026, 11, 15, 0, 0, 0, TimeSpan.Zero));

        // Sep 1, Oct 1, Nov 1 have passed; Dec 1 has not.
        await Assert.That(due.Count).IsEqualTo(3);
        await Assert.That(due[0]).IsEqualTo(Start);
    }

    [Test]
    public async Task Generated_through_prevents_double_materialization()
    {
        var rule = MonthlySalary();
        rule.GeneratedThrough = new DateTimeOffset(2026, 10, 2, 0, 0, 0, TimeSpan.Zero);

        var due = rule.DueOccurrences(new DateTimeOffset(2026, 11, 15, 0, 0, 0, TimeSpan.Zero));
        await Assert.That(due.Count).IsEqualTo(1); // only Nov 1

        await Assert.That(rule.DueOccurrences(rule.GeneratedThrough.Value).Count).IsEqualTo(0);
    }

    [Test]
    public async Task Bounded_rules_report_exhaustion()
    {
        var schedule = RecurrenceSchedule.Create(
            new RecurrenceSpec(Frequency.Daily, Count: 2),
            Start
        ).AsT1;
        var rule = MonthlySalary();
        var bounded = new RecurringTransaction
        {
            UserId = rule.UserId,
            AccountId = rule.AccountId,
            Kind = rule.Kind,
            Amount = rule.Amount,
            Currency = rule.Currency,
            Rrule = schedule.Rrule,
            TimeZoneId = schedule.TimeZoneId,
            StartDate = schedule.Start,
        };

        await Assert.That(bounded.DueOccurrences(Start.AddDays(10)).Count).IsEqualTo(2);
        await Assert.That(bounded.IsExhaustedAfter(Start.AddDays(10))).IsTrue();
        await Assert.That(bounded.IsExhaustedAfter(Start)).IsFalse();
    }
}
