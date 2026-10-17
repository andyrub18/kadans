using Kadans.Modules.Budget.Domain;
using Kadans.Modules.Budget.Features;

namespace Kadans.Budget.Tests;

/// <summary>A category's limit: its periods, its spending in other currencies at the person's rates, and its alerts.</summary>
public class CategoryLimitTests
{
    private static readonly TimeZoneInfo PortAuPrince = TimeZoneInfo.FindSystemTimeZoneById("America/Port-au-Prince");
    private static readonly DateTimeOffset T0 = new(2026, 10, 18, 3, 30, 0, TimeSpan.Zero); // Sat 17 Oct, 23:30 there

    private static CategoryBudget Limit(decimal amount = 15_000m, Currency currency = Currency.Htg, LimitPeriod period = LimitPeriod.Month) =>
        new() { UserId = "u1", CategoryId = Guid.CreateVersion7(), Currency = currency, Amount = amount, Period = period };

    // 1 USD = 132 HTG, 1 EUR has no rate.
    private static readonly RateBook Rates = new(Currency.Htg, new Dictionary<Currency, decimal> { [Currency.Usd] = 132m });

    [Test]
    public async Task A_week_runs_from_monday_and_a_month_and_a_year_from_their_first_day_there()
    {
        var week = CategoryBudget.PeriodAt(LimitPeriod.Week, T0, PortAuPrince);
        await Assert.That(week.Start).IsEqualTo(new DateTimeOffset(2026, 10, 12, 4, 0, 0, TimeSpan.Zero)); // Monday 00:00, UTC-4
        await Assert.That(week.End).IsEqualTo(new DateTimeOffset(2026, 10, 19, 4, 0, 0, TimeSpan.Zero));

        // A Monday is the first day of its own week.
        var monday = CategoryBudget.PeriodAt(LimitPeriod.Week, week.End, PortAuPrince);
        await Assert.That(monday.Start).IsEqualTo(week.End);

        // November starts on summer time and ends on winter time (Haiti's clocks go back on 1 November 2026).
        var november = CategoryBudget.PeriodAt(LimitPeriod.Month, new DateTimeOffset(2026, 11, 15, 12, 0, 0, TimeSpan.Zero), PortAuPrince);
        await Assert.That(november.Start).IsEqualTo(new DateTimeOffset(2026, 11, 1, 4, 0, 0, TimeSpan.Zero));
        await Assert.That(november.End).IsEqualTo(new DateTimeOffset(2026, 12, 1, 5, 0, 0, TimeSpan.Zero));

        var year = CategoryBudget.PeriodAt(LimitPeriod.Year, T0, PortAuPrince);
        await Assert.That(year.Start).IsEqualTo(new DateTimeOffset(2026, 1, 1, 5, 0, 0, TimeSpan.Zero));
        await Assert.That(year.End).IsEqualTo(new DateTimeOffset(2027, 1, 1, 5, 0, 0, TimeSpan.Zero));
    }

    [Test]
    public async Task Spending_in_other_currencies_counts_at_the_persons_rates_and_one_without_a_rate_is_left_out()
    {
        var use = Limit().Use([(Currency.Htg, 10_000m), (Currency.Usd, 20m), (Currency.Eur, 30m)], Rates);

        await Assert.That(use.Spent).IsEqualTo(12_640m); // 10 000 + 20 × 132
        await Assert.That(use.Percent).IsEqualTo(84);
        await Assert.That(use.Converted).IsEquivalentTo([Currency.Usd]);
        await Assert.That(use.MissingRates).IsEquivalentTo([Currency.Eur]);

        // A limit in dollars prices gourdes the other way, through the base.
        var dollars = Limit(100m, Currency.Usd).Use([(Currency.Htg, 6_600m), (Currency.Usd, 30m)], Rates);
        await Assert.That(dollars.Spent).IsEqualTo(80m);
        await Assert.That(dollars.Percent).IsEqualTo(80);
        await Assert.That(dollars.Converted).IsEquivalentTo([Currency.Htg]);
    }

    [Test]
    public async Task A_percent_is_whole_and_rounded_down()
    {
        await Assert.That(Limit(100m).Use([(Currency.Htg, 79.99m)], Rates).Percent).IsEqualTo(79);
        await Assert.That(Limit(100m).Use([], Rates).Percent).IsEqualTo(0);
        await Assert.That(Limit(0.01m).Use([(Currency.Htg, 1_000_000m)], Rates).Percent).IsEqualTo(9999);
    }

    [Test]
    public async Task Alerts_go_out_at_80_and_100_percent_once_each_per_period()
    {
        var limit = Limit();
        var october = new DateTimeOffset(2026, 10, 1, 4, 0, 0, TimeSpan.Zero);
        var november = new DateTimeOffset(2026, 11, 1, 4, 0, 0, TimeSpan.Zero);

        await Assert.That(limit.AlertDue(79, october)).IsNull();
        await Assert.That(limit.AlertDue(80, october)).IsEqualTo(80);
        limit.MarkAlerted(80, october);
        await Assert.That(limit.AlertDue(95, october)).IsNull();
        await Assert.That(limit.AlertDue(100, october)).IsEqualTo(100);
        limit.MarkAlerted(100, october);
        await Assert.That(limit.AlertDue(140, october)).IsNull();

        // A new period starts afresh; a jump past both sends the higher one only.
        await Assert.That(limit.AlertDue(85, november)).IsEqualTo(80);
        await Assert.That(limit.AlertDue(120, november)).IsEqualTo(100);
    }

    [Test]
    public async Task A_new_amount_or_period_alerts_again()
    {
        var limit = Limit(10_000m);
        var october = new DateTimeOffset(2026, 10, 1, 4, 0, 0, TimeSpan.Zero);
        limit.MarkAlerted(100, october);

        limit.Change(10_000m, LimitPeriod.Month, T0); // nothing changed: nothing alerts again
        await Assert.That(limit.AlertDue(100, october)).IsNull();

        limit.Change(15_000m, LimitPeriod.Month, T0); // raised: 80% of the new amount is news
        await Assert.That(limit.AlertDue(82, october)).IsEqualTo(80);
    }

    [Test]
    public async Task The_alert_says_the_percent_and_the_amounts_in_the_persons_language()
    {
        var category = new Category { UserId = "u1", Name = "Food", Kind = CategoryKind.Expense, Icon = "🍚" };
        var limit = new CategoryBudget
        {
            UserId = "u1", CategoryId = category.Id, Category = category, Currency = Currency.Htg, Amount = 15_000m,
        };
        var use = limit.Use([(Currency.Htg, 10_000m), (Currency.Usd, 20m)], Rates);

        var near = CategoryLimitAlerts.Message(limit, use, 80, BudgetTexts.Limit("en"));
        await Assert.That(near.Kind).IsEqualTo("budget.limit");
        await Assert.That(near.Title).IsEqualTo("🍚 Food");
        await Assert.That(near.Body).IsEqualTo("84% of this month's limit: ≈ 12 640.00 HTG of 15 000.00 HTG.");
        await Assert.That(near.Data!["link"]).IsEqualTo("kadans://budget");

        var over = limit.Use([(Currency.Htg, 15_800m)], Rates);
        var reached = CategoryLimitAlerts.Message(limit, over, 100, BudgetTexts.Limit("fr"));
        await Assert.That(reached.Body).IsEqualTo("Limite du mois atteinte : 15 800.00 HTG sur 15 000.00 HTG (105 %).");
    }
}
