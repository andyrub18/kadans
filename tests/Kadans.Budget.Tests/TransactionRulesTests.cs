using Kadans.Modules.Budget.Domain;
using Kadans.SharedKernel.Errors;

namespace Kadans.Budget.Tests;

public class TransactionRulesTests
{
    private static readonly DateTimeOffset T0 = new(2026, 9, 1, 12, 0, 0, TimeSpan.Zero);

    private static Account Htg(string name = "Cash") =>
        new() { UserId = "u1", Name = name, Currency = Currency.Htg };

    private static Account Usd(string name = "Savings") =>
        new() { UserId = "u1", Name = name, Currency = Currency.Usd };

    private static Category Groceries() =>
        new() { UserId = "u1", Name = "Groceries", Kind = CategoryKind.Expense };

    private static Category Salary() =>
        new() { UserId = "u1", Name = "Salary", Kind = CategoryKind.Income };

    [Test]
    public async Task Expense_with_matching_category_carries_the_account_currency()
    {
        var account = Htg();
        var result = Transaction.Create("u1", account, TransactionKind.Expense, 750.25m, T0, Groceries(), " market ", null, null);

        await Assert.That(result.IsT1).IsTrue();
        await Assert.That(result.AsT1.Currency).IsEqualTo(Currency.Htg);
        await Assert.That(result.AsT1.Note).IsEqualTo("market");
        await Assert.That(result.AsT1.EffectOn(account.Id)).IsEqualTo(-750.25m);
    }

    [Test]
    public async Task Category_kind_must_match_the_transaction_kind()
    {
        var result = Transaction.Create("u1", Htg(), TransactionKind.Expense, 100m, T0, Salary(), "", null, null);
        await Assert.That(result.AsT0.ErrorType).IsEqualTo(ErrorTypes.CategoryKindMismatch);
    }

    [Test]
    public async Task Same_currency_transfer_moves_one_amount()
    {
        var source = Htg("Cash");
        var destination = Htg("Bank");
        var result = Transaction.Create("u1", source, TransactionKind.Transfer, 5000m, T0, null, "", destination, null);

        await Assert.That(result.IsT1).IsTrue();
        await Assert.That(result.AsT1.TransferAmount).IsEqualTo(5000m);
        await Assert.That(result.AsT1.EffectOn(source.Id)).IsEqualTo(-5000m);
        await Assert.That(result.AsT1.EffectOn(destination.Id)).IsEqualTo(5000m);
    }

    [Test]
    public async Task Cross_currency_transfer_requires_the_received_amount()
    {
        var gourdes = Htg();
        var dollars = Usd();

        var missing = Transaction.Create("u1", gourdes, TransactionKind.Transfer, 13_200m, T0, null, "", dollars, null);
        await Assert.That(missing.AsT0.ErrorType).IsEqualTo(ErrorTypes.CurrencyMismatch);

        // 13 200 HTG became 100 USD — the pair of amounts IS the exchange rate.
        var exchanged = Transaction.Create("u1", gourdes, TransactionKind.Transfer, 13_200m, T0, null, "", dollars, 100m);
        await Assert.That(exchanged.IsT1).IsTrue();
        await Assert.That(exchanged.AsT1.EffectOn(gourdes.Id)).IsEqualTo(-13_200m);
        await Assert.That(exchanged.AsT1.EffectOn(dollars.Id)).IsEqualTo(100m);
    }

    [Test]
    public async Task Transfers_reject_self_missing_destination_and_categories()
    {
        var account = Htg();
        var self = Transaction.Create("u1", account, TransactionKind.Transfer, 100m, T0, null, "", account, null);
        await Assert.That(self.AsT0.ErrorType).IsEqualTo(ErrorTypes.SameAccountTransfer);

        var nowhere = Transaction.Create("u1", account, TransactionKind.Transfer, 100m, T0, null, "", null, null);
        await Assert.That(nowhere.AsT0.ErrorType).IsEqualTo(ErrorTypes.SameAccountTransfer);

        var categorized = Transaction.Create("u1", account, TransactionKind.Transfer, 100m, T0, Groceries(), "", Htg("Bank"), null);
        await Assert.That(categorized.AsT0.ErrorType).IsEqualTo(ErrorTypes.CategoryKindMismatch);
    }

    [Test]
    public async Task Archived_accounts_take_no_new_money()
    {
        var archived = Htg();
        archived.IsArchived = true;
        var result = Transaction.Create("u1", archived, TransactionKind.Income, 100m, T0, null, "", null, null);
        await Assert.That(result.AsT0.ErrorType).IsEqualTo(ErrorTypes.AccountArchived);
    }
}
