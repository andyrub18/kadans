namespace Kadans.Modules.Budget.Domain;

public enum AccountType
{
    Cash,
    Bank,
    MobileMoney,
    Card,
    Savings,
    Other,
}

/// <summary>A pot of money in exactly one currency. Balances are computed from transactions.</summary>
internal sealed class Account
{
    public Guid Id { get; init; } = Guid.CreateVersion7();
    public required string UserId { get; init; }
    public required string Name { get; set; }
    public required Currency Currency { get; init; }
    public AccountType Type { get; set; } = AccountType.Cash;

    /// <summary>What was in the pot before Kadans started tracking it.</summary>
    public decimal InitialBalance { get; set; }

    public bool IsArchived { get; set; }
    public DateTimeOffset CreatedAt { get; init; } = DateTimeOffset.UtcNow;
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.UtcNow;
}
