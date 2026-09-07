using Kadans.Modules.Budget.Contracts;
using Kadans.Modules.Budget.Domain;
using Kadans.Modules.Budget.Features;

namespace Kadans.Budget.Tests;

public class ExchangeRateTests
{
    private static readonly DateTimeOffset T0 = DateTimeOffset.UtcNow;

    private static AccountResponse Account(Currency currency, decimal balance, bool archived = false) =>
        new(Guid.CreateVersion7(), "a", currency, AccountType.Cash, 0, balance, archived, T0, T0);

    [Test]
    public async Task Combined_estimate_converts_dollars_at_the_users_rate()
    {
        List<CurrencyTotals> totals =
        [
            new(Currency.Htg, Income: 85_000m, Expense: 20_000m, Net: 65_000m),
            new(Currency.Usd, Income: 100m, Expense: 40m, Net: 60m),
        ];
        List<AccountResponse> accounts =
        [
            Account(Currency.Htg, 65_299.50m),
            Account(Currency.Usd, 100m),
            Account(Currency.Usd, 1_000_000m, archived: true), // archived money stays out
        ];

        var combined = TransactionService.Combine(132.50m, totals, accounts);

        await Assert.That(combined).IsNotNull();
        await Assert.That(combined!.HtgPerUsd).IsEqualTo(132.50m);
        await Assert.That(combined.Income).IsEqualTo(85_000m + 13_250m);
        await Assert.That(combined.Expense).IsEqualTo(20_000m + 5_300m);
        await Assert.That(combined.Net).IsEqualTo(combined.Income - combined.Expense);
        await Assert.That(combined.TotalBalance).IsEqualTo(65_299.50m + 13_250m);
    }

    [Test]
    public async Task No_rate_means_no_estimate()
    {
        var combined = TransactionService.Combine(null, [], []);
        await Assert.That(combined).IsNull();
    }
}
