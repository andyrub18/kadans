using Kadans.SharedKernel.Errors;
using OneOf;

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
    public required Guid AccountId { get; init; }
    public Account? Account { get; init; }
    public required TransactionKind Kind { get; init; }
    public required decimal Amount { get; set; }

    /// <summary>Denormalized from the account so summaries never join for it.</summary>
    public required Currency Currency { get; init; }

    public required DateTimeOffset OccurredAt { get; set; }
    public Guid? CategoryId { get; set; }
    public Category? Category { get; set; }
    public string Note { get; set; } = "";

    public Guid? TransferAccountId { get; init; }
    public Account? TransferAccount { get; init; }
    public decimal? TransferAmount { get; set; }

    /// <summary>Set when a recurring rule materialized this row.</summary>
    public Guid? RecurringTransactionId { get; init; }

    public DateTimeOffset CreatedAt { get; init; } = DateTimeOffset.UtcNow;
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.UtcNow;

    /// <summary>
    /// The one place the income/expense/transfer rules live. Pure — the service resolves the
    /// accounts and category, this decides whether the combination is legal.
    /// </summary>
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
        if (account.IsArchived)
            return new ApplicationError(ErrorTypes.AccountArchived, "This account is archived.");

        if (Money.ValidateAmount(amount).IsT0)
            return Money.ValidateAmount(amount).AsT0;

        if (kind == TransactionKind.Transfer)
        {
            if (transferAccount is null)
                return new ApplicationError(ErrorTypes.SameAccountTransfer, "A transfer needs a destination account.");
            if (transferAccount.Id == account.Id)
                return new ApplicationError(ErrorTypes.SameAccountTransfer, "A transfer needs two different accounts.");
            if (transferAccount.IsArchived)
                return new ApplicationError(ErrorTypes.AccountArchived, "The destination account is archived.");
            if (category is not null)
                return new ApplicationError(ErrorTypes.CategoryKindMismatch, "Transfers have no category.");

            if (account.Currency == transferAccount.Currency)
            {
                if (transferAmount is not null && transferAmount != amount)
                    return new ApplicationError(ErrorTypes.CurrencyMismatch, "Same-currency transfers move one single amount.");
                transferAmount = amount;
            }
            else
            {
                if (transferAmount is null)
                    return new ApplicationError(ErrorTypes.CurrencyMismatch,
                        $"Transferring {account.Currency} to {transferAccount.Currency} needs the received amount (the exchange).");
                if (Money.ValidateAmount(transferAmount.Value).IsT0)
                    return Money.ValidateAmount(transferAmount.Value).AsT0;
            }
        }
        else
        {
            if (transferAccount is not null || transferAmount is not null)
                return new ApplicationError(ErrorTypes.SameAccountTransfer, "Only transfers involve a second account.");
            if (category is not null)
            {
                var expected = kind == TransactionKind.Income ? CategoryKind.Income : CategoryKind.Expense;
                if (category.Kind != expected)
                    return new ApplicationError(ErrorTypes.CategoryKindMismatch,
                        $"'{category.Name}' is a {category.Kind} category; this is {kind}.");
            }
        }

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
            TransferAmount = kind == TransactionKind.Transfer ? transferAmount : null,
            RecurringTransactionId = recurringTransactionId,
        };
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
