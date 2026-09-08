namespace Kadans.Modules.Budget.Domain;

/// <summary>The user's day-to-day currency — what combined estimates are expressed in.</summary>
internal sealed class BudgetProfile
{
    public required string UserId { get; init; }
    public Currency BaseCurrency { get; set; } = Currency.Htg;
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.UtcNow;
}

/// <summary>
/// An indicative rate: 1 <see cref="Currency"/> = <see cref="RateInBase"/> of the user's base
/// currency, as of when they last updated it. Real exchanges never use this — a transfer
/// records both actual amounts, and that pair IS the rate for that date. This one only
/// pre-fills transfers and prices unconverted foreign holdings in today's estimate.
/// </summary>
internal sealed class CurrencyRate
{
    public Guid Id { get; init; } = Guid.CreateVersion7();
    public required string UserId { get; init; }
    public required Currency Currency { get; init; }
    public required decimal RateInBase { get; set; }
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.UtcNow;
}
