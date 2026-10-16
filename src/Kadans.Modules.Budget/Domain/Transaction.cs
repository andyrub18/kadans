using Kadans.SharedKernel.Errors;
using OneOf;
using OneOf.Types;

namespace Kadans.Modules.Budget.Domain;

public enum TransactionKind
{
    Income,
    Expense,
    Transfer,
}

/// <summary>
/// One movement of money. Income and expense touch one account; a transfer is a single row that
/// leaves <see cref="AccountId"/> (<see cref="Amount"/>, source currency) and enters
/// <see cref="TransferAccountId"/> (<see cref="TransferAmount"/>, destination currency — equal to
/// Amount when both accounts share a currency, explicit when they don't: that IS the exchange).
/// </summary>
internal sealed class Transaction
{
    public Guid Id { get; init; } = Guid.CreateVersion7();
    public required string UserId { get; init; }
    public required Guid AccountId { get; set; }
    public Account? Account { get; set; }
    public required TransactionKind Kind { get; init; }
    public required decimal Amount { get; set; }

    /// <summary>Denormalized from the account so summaries never join for it.</summary>
    public required Currency Currency { get; set; }

    public required DateTimeOffset OccurredAt { get; set; }
    public Guid? CategoryId { get; set; }
    public Category? Category { get; set; }
    public string Note { get; set; } = "";

    /// <summary>The longest note a movement keeps (its column's size); a recurring rule's too.</summary>
    public const int MaxNoteLength = 1000;

    public Guid? TransferAccountId { get; set; }
    public Account? TransferAccount { get; set; }
    public decimal? TransferAmount { get; set; }

    /// <summary>Set when a recurring rule materialized this row.</summary>
    public Guid? RecurringTransactionId { get; init; }

    public DateTimeOffset CreatedAt { get; init; } = DateTimeOffset.UtcNow;
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.UtcNow;

    /// <summary>
    /// The one place the income/expense/transfer rules live, for a movement new or edited, booked by hand or by a
    /// recurring rule. Pure — the service resolves the accounts and category, this decides whether the combination is
    /// legal. Archived accounts are the callers' (<see cref="CheckNew"/>, <see cref="Edit"/>). Gives the amount the
    /// destination receives: the amount itself between two accounts of one currency, null for income and expense.
    /// </summary>
    public static OneOf<ApplicationError, decimal?> Check(
        Account account,
        TransactionKind kind,
        decimal amount,
        Category? category,
        string note,
        Account? transferAccount,
        decimal? transferAmount
    )
    {
        if (Money.ValidateAmount(amount).IsT0)
            return Money.ValidateAmount(amount).AsT0;
        if (note.Trim().Length > MaxNoteLength)
            return new ApplicationError(ErrorTypes.ValidationError, "A movement's note is at most 1,000 characters.");

        if (kind == TransactionKind.Transfer)
        {
            if (transferAccount is null)
                return new ApplicationError(ErrorTypes.SameAccountTransfer, "A transfer needs a destination account.");
            if (transferAccount.Id == account.Id)
                return new ApplicationError(ErrorTypes.SameAccountTransfer, "A transfer needs two different accounts.");
            if (category is not null)
                return new ApplicationError(ErrorTypes.CategoryKindMismatch, "Transfers have no category.");

            if (account.Currency == transferAccount.Currency)
            {
                if (transferAmount is not null && transferAmount != amount)
                    return new ApplicationError(ErrorTypes.CurrencyMismatch, "Same-currency transfers move one single amount.");
                return amount;
            }
            if (transferAmount is null)
                return new ApplicationError(ErrorTypes.CurrencyMismatch,
                    $"Transferring {account.Currency} to {transferAccount.Currency} needs the received amount (the exchange).");
            if (Money.ValidateAmount(transferAmount.Value).IsT0)
                return Money.ValidateAmount(transferAmount.Value).AsT0;
            return transferAmount;
        }

        if (transferAccount is not null || transferAmount is not null)
            return new ApplicationError(ErrorTypes.SameAccountTransfer, "Only transfers involve a second account.");
        if (category is not null)
        {
            var expected = kind == TransactionKind.Income ? CategoryKind.Income : CategoryKind.Expense;
            if (category.Kind != expected)
                return new ApplicationError(ErrorTypes.CategoryKindMismatch,
                    $"'{category.Name}' is a {category.Kind} category; this is {kind}.");
        }
        return (decimal?)null;
    }

    /// <summary><see cref="Check"/> for money that is new (a movement recorded, a rule made): none lands on an archived account.</summary>
    public static OneOf<ApplicationError, decimal?> CheckNew(
        Account account,
        TransactionKind kind,
        decimal amount,
        Category? category,
        string note,
        Account? transferAccount,
        decimal? transferAmount
    )
    {
        if (account.IsArchived)
            return new ApplicationError(ErrorTypes.AccountArchived, "This account is archived.");
        var checkedMove = Check(account, kind, amount, category, note, transferAccount, transferAmount);
        if (checkedMove.IsT1 && transferAccount is { IsArchived: true })
            return new ApplicationError(ErrorTypes.AccountArchived, "The destination account is archived.");
        return checkedMove;
    }

    public static OneOf<ApplicationError, Transaction> Create(
        string userId,
        Account account,
        TransactionKind kind,
        decimal amount,
        DateTimeOffset occurredAt,
        Category? category,
        string note,
        Account? transferAccount,
        decimal? transferAmount,
        Guid? recurringTransactionId = null
    )
    {
        var checkedMove = CheckNew(account, kind, amount, category, note, transferAccount, transferAmount);
        if (checkedMove.IsT0)
            return checkedMove.AsT0;

        return new Transaction
        {
            UserId = userId,
            AccountId = account.Id,
            Kind = kind,
            Amount = amount,
            Currency = account.Currency,
            OccurredAt = occurredAt,
            CategoryId = category?.Id,
            Note = note.Trim(),
            TransferAccountId = transferAccount?.Id,
            TransferAmount = checkedMove.AsT1,
            RecurringTransactionId = recurringTransactionId,
        };
    }

    /// <summary>
    /// The person corrects a movement: its amounts, day, category, note, and the account or accounts it touches (an
    /// expense put on the wrong one). Its kind stays: an income that was an expense is deleted and recorded again. It may
    /// stay on an archived account (fixing an old movement) but never move onto one. One a recurring rule booked changes
    /// alone: the rule books its next ones as before.
    /// </summary>
    public OneOf<ApplicationError, Success> Edit(
        Account account,
        decimal amount,
        DateTimeOffset occurredAt,
        Category? category,
        string note,
        Account? transferAccount,
        decimal? transferAmount,
        DateTimeOffset now
    )
    {
        if (account.IsArchived && account.Id != AccountId)
            return new ApplicationError(ErrorTypes.AccountArchived, "This account is archived.");
        var checkedMove = Check(account, Kind, amount, category, note, transferAccount, transferAmount);
        if (checkedMove.IsT0)
            return checkedMove.AsT0;
        if (transferAccount is { IsArchived: true } && transferAccount.Id != TransferAccountId)
            return new ApplicationError(ErrorTypes.AccountArchived, "The destination account is archived.");

        Account = account;
        AccountId = account.Id;
        Currency = account.Currency;
        TransferAccount = transferAccount;
        TransferAccountId = transferAccount?.Id;
        TransferAmount = checkedMove.AsT1;
        Amount = amount;
        OccurredAt = occurredAt;
        Category = category;
        CategoryId = category?.Id;
        Note = note.Trim();
        UpdatedAt = now;
        return new Success();
    }

    /// <summary>Signed effect of this row on <paramref name="accountId"/>'s balance.</summary>
    public decimal EffectOn(Guid accountId) => Kind switch
    {
        TransactionKind.Income when AccountId == accountId => Amount,
        TransactionKind.Expense when AccountId == accountId => -Amount,
        TransactionKind.Transfer when AccountId == accountId => -Amount,
        TransactionKind.Transfer when TransferAccountId == accountId => TransferAmount ?? 0m,
        _ => 0m,
    };
}
