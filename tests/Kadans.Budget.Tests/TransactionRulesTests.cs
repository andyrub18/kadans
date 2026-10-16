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

    [Test]
    public async Task A_note_is_at_most_1000_characters()
    {
        var kept = Transaction.Create("u1", Htg(), TransactionKind.Expense, 100m, T0, null, new string('x', 1000), null, null);
        await Assert.That(kept.IsT1).IsTrue();

        var refused = Transaction.Create("u1", Htg(), TransactionKind.Expense, 100m, T0, null, new string('x', 1001), null, null);
        await Assert.That(refused.AsT0.ErrorType).IsEqualTo(ErrorTypes.ValidationError);
    }

    [Test]
    public async Task An_expense_on_the_wrong_account_moves_to_the_right_one_in_its_currency()
    {
        var cash = Htg();
        var dollars = Usd("Card");
        var expense = Transaction.Create("u1", cash, TransactionKind.Expense, 40m, T0, Groceries(), "", null, null).AsT1;
        var later = T0.AddDays(1);

        var edited = expense.Edit(dollars, 35m, T0.AddHours(-2), null, " lunch ", null, null, later);

        await Assert.That(edited.IsT1).IsTrue();
        await Assert.That(expense.AccountId).IsEqualTo(dollars.Id);
        await Assert.That(expense.Currency).IsEqualTo(Currency.Usd);
        await Assert.That(expense.CategoryId).IsNull();
        await Assert.That(expense.Note).IsEqualTo("lunch");
        await Assert.That(expense.OccurredAt).IsEqualTo(T0.AddHours(-2));
        await Assert.That(expense.UpdatedAt).IsEqualTo(later);
        await Assert.That(expense.EffectOn(cash.Id)).IsEqualTo(0m);
        await Assert.That(expense.EffectOn(dollars.Id)).IsEqualTo(-35m);
    }

    [Test]
    public async Task An_edit_may_stay_on_an_archived_account_but_never_move_onto_one()
    {
        var old = Htg("Old wallet");
        var expense = Transaction.Create("u1", old, TransactionKind.Expense, 100m, T0, null, "", null, null).AsT1;
        old.IsArchived = true;

        // Fixing an old movement of a closed account.
        await Assert.That(expense.Edit(old, 90m, T0, null, "fixed", null, null, T0).IsT1).IsTrue();

        var closed = Htg("Closed");
        closed.IsArchived = true;
        var moved = expense.Edit(closed, 90m, T0, null, "", null, null, T0);
        await Assert.That(moved.AsT0.ErrorType).IsEqualTo(ErrorTypes.AccountArchived);
        await Assert.That(expense.AccountId).IsEqualTo(old.Id);
    }

    [Test]
    public async Task An_edited_transfer_follows_its_two_currencies()
    {
        var cash = Htg("Cash");
        var bank = Htg("Bank");
        var savings = Usd();
        var transfer = Transaction.Create("u1", cash, TransactionKind.Transfer, 5000m, T0, null, "", bank, null).AsT1;

        // To a dollar account: the received amount is the exchange, and it is needed.
        var missing = transfer.Edit(cash, 13_200m, T0, null, "", savings, null, T0);
        await Assert.That(missing.AsT0.ErrorType).IsEqualTo(ErrorTypes.CurrencyMismatch);
        await Assert.That(transfer.TransferAccountId).IsEqualTo(bank.Id);

        await Assert.That(transfer.Edit(cash, 13_200m, T0, null, "", savings, 100m, T0).IsT1).IsTrue();
        await Assert.That(transfer.EffectOn(savings.Id)).IsEqualTo(100m);
        await Assert.That(transfer.EffectOn(bank.Id)).IsEqualTo(0m);

        // Back between gourdes: one amount again, whatever received amount came along.
        await Assert.That(transfer.Edit(cash, 6000m, T0, null, "", bank, null, T0).IsT1).IsTrue();
        await Assert.That(transfer.TransferAmount).IsEqualTo(6000m);
    }

    [Test]
    public async Task An_edit_keeps_the_kinds_rules()
    {
        var cash = Htg();
        var expense = Transaction.Create("u1", cash, TransactionKind.Expense, 100m, T0, null, "", null, null).AsT1;
        var income = expense.Edit(cash, 100m, T0, Salary(), "", null, null, T0);
        await Assert.That(income.AsT0.ErrorType).IsEqualTo(ErrorTypes.CategoryKindMismatch);
        var withDestination = expense.Edit(cash, 100m, T0, null, "", Htg("Bank"), null, T0);
        await Assert.That(withDestination.AsT0.ErrorType).IsEqualTo(ErrorTypes.SameAccountTransfer);

        var bank = Htg("Bank");
        var transfer = Transaction.Create("u1", cash, TransactionKind.Transfer, 100m, T0, null, "", bank, null).AsT1;
        var toItself = transfer.Edit(bank, 100m, T0, null, "", bank, null, T0);
        await Assert.That(toItself.AsT0.ErrorType).IsEqualTo(ErrorTypes.SameAccountTransfer);
        await Assert.That(transfer.AccountId).IsEqualTo(cash.Id);
    }

    [Test]
    public async Task A_recurring_transfer_is_checked_like_one_recorded_by_hand()
    {
        var salary = Usd("Salary");
        var savings = Usd("Savings");

        var monthly = Transaction.CheckNew(salary, TransactionKind.Transfer, 100m, null, "", savings, null);
        await Assert.That(monthly.AsT1).IsEqualTo(100m);

        savings.IsArchived = true;
        var closed = Transaction.CheckNew(salary, TransactionKind.Transfer, 100m, null, "", savings, null);
        await Assert.That(closed.AsT0.ErrorType).IsEqualTo(ErrorTypes.AccountArchived);

        var gourdes = Htg();
        var exchange = Transaction.CheckNew(gourdes, TransactionKind.Transfer, 13_200m, null, "", Usd(), null);
        await Assert.That(exchange.AsT0.ErrorType).IsEqualTo(ErrorTypes.CurrencyMismatch);
    }
}
