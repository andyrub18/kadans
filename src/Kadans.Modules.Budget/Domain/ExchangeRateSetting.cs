namespace Kadans.Modules.Budget.Domain;

/// <summary>
/// The user's own HTG-per-USD rate — it changes daily and they update it when they care.
/// It never rewrites stored transactions (those keep their two real amounts); it only
/// pre-fills transfers and powers "everything together, at your rate" estimates.
/// </summary>
internal sealed class ExchangeRateSetting
{
    public required string UserId { get; init; }
    public required decimal HtgPerUsd { get; set; }
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.UtcNow;
}
