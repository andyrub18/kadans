using Kadans.Modules.Budget.Contracts;
using Kadans.Modules.Budget.Domain;
using Kadans.Modules.Budget.Features;

namespace Kadans.Budget.Tests;

public class CombinedEstimateTests
{
    private static readonly DateTimeOffset T0 = DateTimeOffset.UtcNow;

    private static AccountResponse Account(Currency currency, decimal balance, bool archived = false) =>
        new(Guid.CreateVersion7(), "a", currency, AccountType.Cash, 0, balance, archived, T0, T0);

    [Test]
    public async Task Converts_each_currency_at_its_own_indicative_rate()
    {
        List<CurrencyTotals> totals =
        [
            new(Currency.Htg, Income: 85_000m, Expense: 20_000m, Net: 65_000m),
            new(Currency.Usd, Income: 100m, Expense: 40m, Net: 60m),
            new(Currency.Dop, Income: 1_000m, Expense: 0m, Net: 1_000m),
        ];
        List<AccountResponse> accounts =
        [
            Account(Currency.Htg, 65_299.50m),
            Account(Currency.Usd, 100m),
            Account(Currency.Dop, 500m),
            Account(Currency.Eur, 1_000_000m, archived: true), // archived money stays out entirely
        ];
        var rates = new Dictionary<Currency, decimal>
        {
            [Currency.Usd] = 132.50m,
            [Currency.Dop] = 2.20m,
        };

        var combined = TransactionService.Combine(Currency.Htg, rates, totals, accounts);

        await Assert.That(combined.BaseCurrency).IsEqualTo(Currency.Htg);
        await Assert.That(combined.Income).IsEqualTo(85_000m + 13_250m + 2_200m);
        await Assert.That(combined.Expense).IsEqualTo(20_000m + 5_300m);
        await Assert.That(combined.Net).IsEqualTo(combined.Income - combined.Expense);
        await Assert.That(combined.TotalBalance).IsEqualTo(65_299.50m + 13_250m + 1_100m);
        await Assert.That(combined.MissingRates).IsEmpty();
    }

    [Test]
    public async Task Currencies_without_a_rate_are_excluded_and_reported_never_guessed()
    {
        List<CurrencyTotals> totals = [new(Currency.Htg, 1_000m, 0m, 1_000m)];
        List<AccountResponse> accounts =
        [
            Account(Currency.Htg, 1_000m),
            Account(Currency.Eur, 300m),   // diaspora money, no rate entered yet
            Account(Currency.Cad, 200m),
        ];

        var combined = TransactionService.Combine(Currency.Htg, new Dictionary<Currency, decimal>(), totals, accounts);

        await Assert.That(combined.TotalBalance).IsEqualTo(1_000m);
        await Assert.That(combined.MissingRates).IsEquivalentTo([Currency.Eur, Currency.Cad]);
    }

    [Test]
    public async Task The_base_can_be_something_other_than_gourdes()
    {
        // A user living in USD day-to-day: HTG becomes the foreign currency.
        List<CurrencyTotals> totals = [new(Currency.Htg, 13_250m, 0m, 13_250m)];
        List<AccountResponse> accounts = [Account(Currency.Usd, 500m), Account(Currency.Htg, 13_250m)];
        var rates = new Dictionary<Currency, decimal> { [Currency.Htg] = 0.007547m };

        var combined = TransactionService.Combine(Currency.Usd, rates, totals, accounts);

        await Assert.That(combined.BaseCurrency).IsEqualTo(Currency.Usd);
        await Assert.That(combined.Income).IsEqualTo(100.00m);
        await Assert.That(combined.TotalBalance).IsEqualTo(600.00m);
    }
}
