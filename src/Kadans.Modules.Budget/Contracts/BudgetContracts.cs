using Kadans.Modules.Budget.Domain;
using Kadans.SharedKernel.Recurrence;

namespace Kadans.Modules.Budget.Contracts;

// ---- accounts ----

public sealed record CreateAccount(
    string Name,
    Currency Currency,
    AccountType Type = AccountType.Cash,
    decimal InitialBalance = 0
);

public sealed record UpdateAccount(string Name, AccountType Type, bool IsArchived = false);

public sealed record AccountResponse(
    Guid Id,
    string Name,
    Currency Currency,
    AccountType Type,
    decimal InitialBalance,
    decimal Balance,
    bool IsArchived,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt
);

// ---- categories ----

public sealed record CreateCategory(string Name, CategoryKind Kind, string? Icon = null);

public sealed record UpdateCategory(string Name, string? Icon = null, bool IsArchived = false);

public sealed record CategoryResponse(
    Guid Id,
    string Name,
    CategoryKind Kind,
    string? Icon,
    bool IsArchived
);

/// <summary>Upserts the monthly limit for a category (one per category; currency explicit).</summary>
public sealed record SetCategoryBudget(decimal MonthlyLimit, Currency Currency);

public sealed record CategoryBudgetResponse(Guid CategoryId, decimal MonthlyLimit, Currency Currency);

// ---- transactions ----

/// <summary>
/// Income/expense: account + amount (+ optional matching-kind category). Transfer: also
/// <paramref name="TransferAccountId"/>; when the two accounts' currencies differ,
/// <paramref name="TransferAmount"/> is the amount received (that IS the exchange rate).
/// </summary>
public sealed record CreateTransaction(
    Guid AccountId,
    TransactionKind Kind,
    decimal Amount,
    DateTimeOffset OccurredAt,
    Guid? CategoryId = null,
    string Note = "",
    Guid? TransferAccountId = null,
    decimal? TransferAmount = null
);

public sealed record UpdateTransaction(
    decimal Amount,
    DateTimeOffset OccurredAt,
    Guid? CategoryId = null,
    string Note = "",
    decimal? TransferAmount = null
);

public sealed record TransactionResponse(
    Guid Id,
    Guid AccountId,
    TransactionKind Kind,
    decimal Amount,
    Currency Currency,
    DateTimeOffset OccurredAt,
    Guid? CategoryId,
    string Note,
    Guid? TransferAccountId,
    decimal? TransferAmount,
    Guid? RecurringTransactionId,
    DateTimeOffset CreatedAt
);

// ---- recurring ----

/// <summary>The slice of the recurrence engine budget rules need (salary on the 1st, weekly groceries…).</summary>
public sealed record BudgetRecurrence(
    Frequency Frequency,
    DateTimeOffset StartDate,
    int Interval = 1,
    List<DayOfWeek>? ByDayOfWeek = null,
    List<int>? ByMonthDay = null,
    int? Count = null,
    DateTimeOffset? Until = null,
    string? TimeZone = null
);

public sealed record CreateRecurringTransaction(
    Guid AccountId,
    TransactionKind Kind,
    decimal Amount,
    BudgetRecurrence Recurrence,
    Guid? CategoryId = null,
    string Note = ""
);

public sealed record UpdateRecurringTransaction(
    decimal Amount,
    Guid? CategoryId = null,
    string Note = "",
    bool IsActive = true
);

public sealed record RecurringTransactionResponse(
    Guid Id,
    Guid AccountId,
    TransactionKind Kind,
    decimal Amount,
    Currency Currency,
    Guid? CategoryId,
    string Note,
    string Rrule,
    string TimeZoneId,
    DateTimeOffset StartDate,
    DateTimeOffset? NextAt,
    bool IsActive,
    DateTimeOffset CreatedAt
);

// ---- settings: base currency + indicative rates ----

/// <summary>
/// A real exchange lives in its transaction (the pair of amounts, on that date). These are the
/// user's *indicative* rates — 1 unit of a foreign currency in the base — refreshable whenever,
/// used only to pre-fill transfers and price unconverted holdings in today's estimate.
/// </summary>
public sealed record CurrencyRateResponse(Currency Currency, decimal RateInBase, DateTimeOffset UpdatedAt);

public sealed record BudgetSettingsResponse(Currency BaseCurrency, IReadOnlyList<CurrencyRateResponse> Rates);

/// <summary>Changing the base clears stored rates — they were denominated in the old base.</summary>
public sealed record SetBaseCurrency(Currency BaseCurrency);

public sealed record SetCurrencyRate(decimal RateInBase);

// ---- summary ----

public sealed record CurrencyTotals(Currency Currency, decimal Income, decimal Expense, decimal Net);

public sealed record CategorySpend(
    Guid CategoryId,
    string Name,
    CategoryKind Kind,
    string? Icon,
    Currency Currency,
    decimal Amount,
    decimal? MonthlyLimit
);

/// <summary>
/// Everything expressed in the base currency at the user's indicative rates — an estimate,
/// clearly labeled as such. Currencies without a rate are excluded and listed in
/// <see cref="MissingRates"/>, never silently guessed.
/// </summary>
public sealed record CombinedEstimate(
    Currency BaseCurrency,
    decimal Income,
    decimal Expense,
    decimal Net,
    decimal TotalBalance,
    IReadOnlyList<Currency> MissingRates
);

/// <summary>One month of the user's money, computed in their time zone.</summary>
public sealed record MonthlySummaryResponse(
    int Year,
    int Month,
    string TimeZoneId,
    IReadOnlyList<CurrencyTotals> Totals,
    IReadOnlyList<AccountResponse> Accounts,
    IReadOnlyList<CategorySpend> Categories,
    CombinedEstimate? Combined = null
);
