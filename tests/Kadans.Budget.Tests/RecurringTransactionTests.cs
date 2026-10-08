using Kadans.Modules.Budget.Domain;
using Kadans.SharedKernel.Errors;
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

    [Test]
    [Arguments(Frequency.Minutely)]
    [Arguments(Frequency.Hourly)]
    public async Task Money_rules_repeat_at_most_daily(Frequency frequency)
    {
        await Assert.That(RecurringTransaction.CheckNewSchedule(frequency, Start, Start)!.ErrorType).IsEqualTo(ErrorTypes.InvalidFrequency);
        await Assert.That(RecurringTransaction.CheckNewSchedule(Frequency.Daily, Start, Start)).IsNull();
    }

    [Test]
    public async Task A_money_rule_starts_at_most_a_year_back()
    {
        await Assert.That(RecurringTransaction.CheckNewSchedule(Frequency.Monthly, Start.AddMonths(-11), Start)).IsNull();
        await Assert.That(RecurringTransaction.CheckNewSchedule(Frequency.Monthly, Start.AddYears(-1).AddDays(-1), Start)!.ErrorType)
            .IsEqualTo(ErrorTypes.InvalidStartDate);
    }

    [Test]
    public async Task A_backlog_longer_than_one_pass_resumes_on_the_next_pass()
    {
        // Daily for 250 days before now: 251 instances over three passes of 100, 100 and 51. Before, the first
        // pass moved the marker to now and the other 151 were never created.
        var rule = Daily(Start.AddDays(-250));
        var materialized = new List<DateTimeOffset>();

        for (var pass = 0; pass < 4; pass++)
        {
            var due = rule.DueOccurrences(Start);
            materialized.AddRange(due);
            rule.Advance(due, Start);
        }

        await Assert.That(materialized.Count).IsEqualTo(251);
        await Assert.That(materialized.Distinct().Count()).IsEqualTo(251);
        await Assert.That(rule.GeneratedThrough).IsEqualTo(Start);
    }

    [Test]
    public async Task A_bounded_rule_stays_active_until_its_backlog_is_done()
    {
        // Ended ten days ago, but 241 instances are still owed: exhaustion is judged where materialization stopped.
        var rule = Daily(Start.AddDays(-250), until: Start.AddDays(-10));

        var first = rule.DueOccurrences(Start);
        rule.Advance(first, Start);
        var activeAfterFirstPass = rule.IsActive;
        var total = first.Count;
        while (rule.IsActive && rule.DueOccurrences(Start) is { Count: > 0 } due)
        {
            total += due.Count;
            rule.Advance(due, Start);
        }

        await Assert.That(activeAfterFirstPass).IsTrue();
        await Assert.That(total).IsEqualTo(241);
        await Assert.That(rule.IsActive).IsFalse();
    }

    private static RecurringTransaction Daily(DateTimeOffset start, DateTimeOffset? until = null)
    {
        var schedule = RecurrenceSchedule.Create(new RecurrenceSpec(Frequency.Daily, Until: until), start).AsT1;
        return new RecurringTransaction
        {
            UserId = "u1",
            AccountId = Guid.CreateVersion7(),
            Kind = TransactionKind.Expense,
            Amount = 250m,
            Currency = Currency.Htg,
            Rrule = schedule.Rrule,
            TimeZoneId = schedule.TimeZoneId,
            StartDate = schedule.Start,
        };
    }

    [Test]
    public async Task A_rule_knows_when_it_next_has_something_to_book()
    {
        var rule = MonthlySalary();
        rule.ScheduleNext();
        await Assert.That(rule.NextOccurrenceAt).IsEqualTo(Start); // nothing booked yet: its first instance

        // Booked through mid-November: next is December 1st, and the job will not look at it before.
        var now = new DateTimeOffset(2026, 11, 15, 0, 0, 0, TimeSpan.Zero);
        rule.Advance(rule.DueOccurrences(now), now);
        await Assert.That(rule.NextOccurrenceAt!.Value.Month).IsEqualTo(12);
        await Assert.That(rule.NextOccurrenceAt.Value.Day).IsEqualTo(1);
    }

    [Test]
    public async Task A_paused_or_finished_rule_has_no_next_date()
    {
        var rule = MonthlySalary();
        rule.IsActive = false;
        rule.ScheduleNext();
        await Assert.That(rule.NextOccurrenceAt).IsNull();

        var schedule = RecurrenceSchedule.Create(new RecurrenceSpec(Frequency.Daily, Count: 2), Start).AsT1;
        var twice = new RecurringTransaction
        {
            UserId = "u1", AccountId = Guid.CreateVersion7(), Kind = TransactionKind.Expense, Amount = 10m, Currency = Currency.Htg,
            Rrule = schedule.Rrule, TimeZoneId = schedule.TimeZoneId, StartDate = schedule.Start,
        };
        var later = Start.AddDays(5);
        twice.Advance(twice.DueOccurrences(later), later);
        await Assert.That(twice.IsActive).IsFalse();
        await Assert.That(twice.NextOccurrenceAt).IsNull();
    }
}
