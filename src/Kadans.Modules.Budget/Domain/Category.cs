namespace Kadans.Modules.Budget.Domain;

public enum CategoryKind
{
    Income,
    Expense,
}

internal sealed class Category
{
    public Guid Id { get; init; } = Guid.CreateVersion7();
    public required string UserId { get; init; }
    public required string Name { get; set; }
    public required CategoryKind Kind { get; init; }

    /// <summary>A short label the client renders, typically an emoji.</summary>
    public string? Icon { get; set; }

    public bool IsArchived { get; set; }
    public DateTimeOffset CreatedAt { get; init; } = DateTimeOffset.UtcNow;
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.UtcNow;
}

/// <summary>How often a category's limit starts again, in the person's time zone: weeks on Monday, years on 1 January.</summary>
public enum LimitPeriod
{
    Week,
    Month,
    Year,
}

/// <summary>
/// A spending limit for one expense category, in a currency fixed when it is set: another currency is another limit
/// (remove this one), so nothing is ever converted after the fact. Spending in other currencies counts at the person's
/// indicative rates (<see cref="RateBook"/>), an estimate; a currency without a rate is left out and said. The person
/// is alerted at <see cref="AlertPercent"/> and at 100%, once each per period.
/// </summary>
internal sealed class CategoryBudget
{
    public Guid Id { get; init; } = Guid.CreateVersion7();
    public required string UserId { get; init; }
    public required Guid CategoryId { get; init; }
    public Category? Category { get; init; }
    public required Currency Currency { get; init; }
    public required decimal Amount { get; set; }
    public LimitPeriod Period { get; set; } = LimitPeriod.Month;
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.UtcNow;

    /// <summary>The start of the period an alert went out in, and the highest one sent then (80 or 100).</summary>
    public DateTimeOffset? AlertedPeriodStart { get; private set; }
    public int AlertedLevel { get; private set; }

    /// <summary>"Near the limit": fixed, the owner's choice (2026-10-17).</summary>
    public const int AlertPercent = 80;

    /// <summary>
    /// A new amount or period: what was alerted no longer says anything about it (a raised limit alerts again at 80% of the
    /// new one).
    /// </summary>
    public void Change(decimal amount, LimitPeriod period, DateTimeOffset now)
    {
        if (amount != Amount || period != Period)
        {
            AlertedPeriodStart = null;
            AlertedLevel = 0;
        }
        Amount = amount;
        Period = period;
        UpdatedAt = now;
    }

    /// <summary>
    /// The alert <paramref name="percent"/> calls for in the period from <paramref name="periodStart"/>, if it has not gone
    /// out yet: 100 once the limit is reached, else <see cref="AlertPercent"/> near it. Jumping past both sends the higher.
    /// </summary>
    public int? AlertDue(int percent, DateTimeOffset periodStart)
    {
        var sent = AlertedPeriodStart == periodStart ? AlertedLevel : 0;
        var level = percent >= 100 ? 100 : percent >= AlertPercent ? AlertPercent : 0;
        return level > sent ? level : null;
    }

    public void MarkAlerted(int level, DateTimeOffset periodStart)
    {
        AlertedPeriodStart = periodStart;
        AlertedLevel = level;
    }

    /// <summary>The period of <paramref name="period"/> that <paramref name="at"/> falls in, bounded in <paramref name="zone"/>.</summary>
    public static (DateTimeOffset Start, DateTimeOffset End) PeriodAt(LimitPeriod period, DateTimeOffset at, TimeZoneInfo zone)
    {
        var local = TimeZoneInfo.ConvertTime(at, zone).DateTime.Date;
        var start = period switch
        {
            LimitPeriod.Week => local.AddDays(-(((int)local.DayOfWeek + 6) % 7)),
            LimitPeriod.Month => new DateTime(local.Year, local.Month, 1),
            _ => new DateTime(local.Year, 1, 1),
        };
        var end = period switch
        {
            LimitPeriod.Week => start.AddDays(7),
            LimitPeriod.Month => start.AddMonths(1),
            _ => start.AddYears(1),
        };
        return (InZone(start, zone), InZone(end, zone));
    }

    private static DateTimeOffset InZone(DateTime local, TimeZoneInfo zone)
    {
        var wall = DateTime.SpecifyKind(local, DateTimeKind.Unspecified);
        return new DateTimeOffset(wall, zone.GetUtcOffset(wall));
    }

    /// <summary>
    /// How much of the limit <paramref name="spending"/> (a period's, per currency) uses, in its currency: other currencies
    /// at <paramref name="rates"/>, those without a rate left out.
    /// </summary>
    public LimitUse Use(IEnumerable<(Currency Currency, decimal Amount)> spending, RateBook rates)
    {
        decimal spent = 0m;
        List<Currency> converted = [], missing = [];
        foreach (var (currency, amount) in spending.Where(s => s.Amount != 0))
        {
            if (rates.Convert(amount, currency, Currency) is not { } inLimit)
            {
                missing.Add(currency);
                continue;
            }
            spent += inLimit;
            if (currency != Currency)
                converted.Add(currency);
        }
        // Whole percents, rounded down: 79.9% is not yet 80. Bounded for a limit of a few cents.
        var percent = (int)Math.Min(decimal.Floor(spent * 100m / Amount), 9999m);
        return new LimitUse(spent, percent, [.. converted.Order()], [.. missing.Order()]);
    }
}

/// <summary>A limit's use: what was spent in its currency, the whole percent, and which currencies were priced or left out.</summary>
internal sealed record LimitUse(decimal Spent, int Percent, IReadOnlyList<Currency> Converted, IReadOnlyList<Currency> MissingRates);

/// <summary>
/// The person's indicative rates (1 unit = <c>rate</c> of the base currency), to price an amount in another currency
/// through the base. Null when either currency has no rate: never guessed.
/// </summary>
internal sealed record RateBook(Currency Base, IReadOnlyDictionary<Currency, decimal> Rates)
{
    public decimal? Convert(decimal amount, Currency from, Currency to)
    {
        if (from == to)
            return amount;
        if (Rate(from) is not { } fromRate || Rate(to) is not { } toRate || toRate <= 0)
            return null;
        return decimal.Round(amount * fromRate / toRate, 2);
    }

    private decimal? Rate(Currency currency) =>
        currency == Base ? 1m : Rates.TryGetValue(currency, out var rate) ? rate : null;
}
