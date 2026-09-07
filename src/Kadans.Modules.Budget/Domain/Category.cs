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

/// <summary>A monthly spending limit for one category, in one currency.</summary>
internal sealed class CategoryBudget
{
    public Guid Id { get; init; } = Guid.CreateVersion7();
    public required string UserId { get; init; }
    public required Guid CategoryId { get; init; }
    public Category? Category { get; init; }
    public required Currency Currency { get; set; }
    public required decimal MonthlyLimit { get; set; }
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.UtcNow;
}
