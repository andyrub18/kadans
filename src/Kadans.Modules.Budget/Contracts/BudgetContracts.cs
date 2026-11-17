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

// ---- a bank statement's lines (ARCHITECTURE → "Statements in, data out") ----

/// <summary>
/// One line of a statement that the person kept: income or an expense (never a transfer), with the key that names the
/// line (<see cref="Domain.Transaction.ImportKey"/>). The statement itself is read by the app and never comes here.
/// </summary>
public sealed record ImportedMovement(
    TransactionKind Kind,
    decimal Amount,
    DateTimeOffset OccurredAt,
    string ImportKey,
    string Note = "",
    Guid? CategoryId = null
);

/// <summary>Up to 500 lines for one account, booked together or not at all; a longer statement comes in several requests.</summary>
public sealed record ImportTransactions(Guid AccountId, List<ImportedMovement> Movements);

/// <param name="Skipped">Lines the account already had (the same statement imported before, or an overlapping one).</param>
public sealed record ImportTransactionsResponse(int Imported, int Skipped);

/// <summary>The category the person gave their latest movement of this kind and text.</summary>
public sealed record SuggestedCategory(TransactionKind Kind, string Note, Guid CategoryId);

/// <summary>
/// What the app needs to sort a statement's lines on the device, before anything of the statement is sent: the keys
/// the account already has, and the person's own movements' texts with their categories.
/// </summary>
/// <param name="ImportKeys">The keys of the account's imported movements, newest first.</param>
/// <param name="Categories">For a kind and a text, the category of the person's latest movement that reads so.</param>
public sealed record ImportKnownResponse(List<string> ImportKeys, List<SuggestedCategory> Categories);

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
