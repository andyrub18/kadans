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

/// <summary>
/// Sets an expense category's limit, or changes its amount and period. One per category, in <paramref name="Currency"/>
/// from the start: a limit keeps it (remove the limit to set one in another currency).
/// </summary>
public sealed record SetCategoryLimit(decimal Amount, Currency Currency, LimitPeriod Period = LimitPeriod.Month);

public sealed record CategoryLimitResponse(Guid CategoryId, decimal Amount, Currency Currency, LimitPeriod Period);

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

/// <summary>
/// A movement corrected: what it carries and, when given, the account (and a transfer's destination) it moves to; an
/// account left out stays. Its kind never changes. No category is none.
/// </summary>
public sealed record UpdateTransaction(
    decimal Amount,
    DateTimeOffset OccurredAt,
    Guid? CategoryId = null,
    string Note = "",
    decimal? TransferAmount = null,
    Guid? AccountId = null,
    Guid? TransferAccountId = null
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

/// <summary>
/// A budget rule's dates, with a todo's day parts (the apps share one rule builder): salary on the 15th and the 30th
/// (<paramref name="ByMonthDay"/>, -1 the last day), "the last Friday" (<paramref name="ByDayOfWeek"/> with
/// <paramref name="BySetPos"/>), some months of the year (<paramref name="ByMonth"/>). Never more than once a day.
/// </summary>
public sealed record BudgetRecurrence(
    Frequency Frequency,
    DateTimeOffset StartDate,
    int Interval = 1,
    List<DayOfWeek>? ByDayOfWeek = null,
    List<int>? ByMonthDay = null,
    int? Count = null,
    DateTimeOffset? Until = null,
    string? TimeZone = null,
    List<int>? BySetPos = null,
    List<int>? ByMonth = null
);

/// <summary>
/// Income, expense, or a transfer (<paramref name="TransferAccountId"/>, and <paramref name="TransferAmount"/> between
/// two currencies: received each time), booked on each date of <paramref name="Recurrence"/>.
/// </summary>
public sealed record CreateRecurringTransaction(
    Guid AccountId,
    TransactionKind Kind,
    decimal Amount,
    BudgetRecurrence Recurrence,
    Guid? CategoryId = null,
    string Note = "",
    Guid? TransferAccountId = null,
    decimal? TransferAmount = null
);

/// <summary>What the next bookings carry (the ones already booked stay), or a pause. The accounts and dates stay.</summary>
public sealed record UpdateRecurringTransaction(
    decimal Amount,
    Guid? CategoryId = null,
    string Note = "",
    bool IsActive = true,
    decimal? TransferAmount = null
);

public sealed record RecurringTransactionResponse(
    Guid Id,
    Guid AccountId,
    TransactionKind Kind,
    decimal Amount,
    Currency Currency,
    Guid? CategoryId,
    string Note,
    Guid? TransferAccountId,
    decimal? TransferAmount,
    string Rrule,
    string TimeZoneId,
    DateTimeOffset StartDate,
    Frequency Frequency,
    int Interval,
    int? Count,
    DateTimeOffset? Until,
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

/// <summary>A category's movements in the month, in one currency (a category paid in two has two).</summary>
public sealed record CategorySpend(
    Guid CategoryId,
    string Name,
    CategoryKind Kind,
    string? Icon,
    Currency Currency,
    decimal Amount
);

/// <summary>
/// A category's limit in the period the month on screen shows: that month, the year through it, or this week (on the
/// current month; another month's is its last week). <paramref name="Spent"/> is in the limit's currency, other
/// currencies at the person's rates (<paramref name="Converted"/>, an estimate); <paramref name="MissingRates"/> spent
/// in the period are left out, never guessed. <paramref name="Percent"/> is whole, rounded down.
/// </summary>
public sealed record CategoryLimitStatus(
    Guid CategoryId,
    decimal Amount,
    Currency Currency,
    LimitPeriod Period,
    DateTimeOffset PeriodStart,
    DateTimeOffset PeriodEnd,
    decimal Spent,
    int Percent,
    IReadOnlyList<Currency> Converted,
    IReadOnlyList<Currency> MissingRates
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
    CombinedEstimate? Combined = null,
    IReadOnlyList<CategoryLimitStatus>? Limits = null
);
