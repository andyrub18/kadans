using Kadans.Modules.Budget.Contracts;
using Kadans.Modules.Budget.Domain;
using Kadans.Modules.Budget.Persistence;
using Kadans.SharedKernel.Errors;
using Kadans.SharedKernel.Http;
using Kadans.SharedKernel.Security;
using Kadans.SharedKernel.Users;
using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.EntityFrameworkCore;
using OneOf;
using OneOf.Types;

namespace Kadans.Modules.Budget.Features;

internal sealed class TransactionService(
    BudgetDbContext context,
    ICurrentUserService currentUser,
    IUserDirectory users
)
{
    public async Task<OneOf<ApplicationError, TransactionResponse>> Create(CreateTransaction request)
    {
        var userId = currentUser.UserId;
        if (string.IsNullOrWhiteSpace(userId))
            return new ApplicationError(ErrorTypes.Unauthorized, "User is not authenticated.");

        var parts = await MovementParts.Find(context, request.AccountId, request.TransferAccountId, request.CategoryId);
        if (parts.IsT0)
            return parts.AsT0;
        var (account, transferAccount, category) = parts.AsT1;

        var created = Transaction.Create(
            userId, account, request.Kind, request.Amount, request.OccurredAt,
            category, request.Note, transferAccount, request.TransferAmount
        );
        if (created.IsT0)
            return created.AsT0;

        context.Transactions.Add(created.AsT1);
        await context.SaveChangesAsync();
        return ToResponse(created.AsT1);
    }

    public async Task<OneOf<ApplicationError, TransactionResponse>> Get(Guid id) =>
        await context.Transactions.AsNoTracking().FirstOrDefaultAsync(t => t.Id == id) is { } transaction
            ? ToResponse(transaction)
            : new ApplicationError(ErrorTypes.TransactionNotFound, $"Transaction {id} not found.");

    public async Task<List<TransactionResponse>> List(
        Guid? accountId,
        Guid? categoryId,
        TransactionKind? kind,
        DateTimeOffset? from,
        DateTimeOffset? to,
        int page = 1,
        int pageSize = 50
    )
    {
        pageSize = Math.Clamp(pageSize, 1, 200);
        var query = context.Transactions.AsNoTracking();
        if (accountId is { } acc)
            query = query.Where(t => t.AccountId == acc || t.TransferAccountId == acc);
        if (categoryId is not null)
            query = query.Where(t => t.CategoryId == categoryId);
        if (kind is not null)
            query = query.Where(t => t.Kind == kind);
        if (from is not null)
            query = query.Where(t => t.OccurredAt >= from);
        if (to is not null)
            query = query.Where(t => t.OccurredAt < to);

        return [.. (await query
            .OrderByDescending(t => t.OccurredAt).ThenByDescending(t => t.Id)
            .Skip((Math.Max(1, page) - 1) * pageSize)
            .Take(pageSize)
            .ToListAsync()).Select(ToResponse)];
    }

    public async Task<OneOf<ApplicationError, TransactionResponse>> Update(Guid id, UpdateTransaction request)
    {
        var transaction = await context.Transactions.FirstOrDefaultAsync(t => t.Id == id);
        if (transaction is null)
            return new ApplicationError(ErrorTypes.TransactionNotFound, $"Transaction {id} not found.");

        var parts = await MovementParts.Find(
            context,
            request.AccountId ?? transaction.AccountId,
            request.TransferAccountId ?? transaction.TransferAccountId,
            request.CategoryId
        );
        if (parts.IsT0)
            return parts.AsT0;
        var (account, transferAccount, category) = parts.AsT1;

        var edited = transaction.Edit(
            account, request.Amount, request.OccurredAt, category, request.Note,
            transferAccount, request.TransferAmount, DateTimeOffset.UtcNow
        );
        if (edited.IsT0)
            return edited.AsT0;
        await context.SaveChangesAsync();
        return ToResponse(transaction);
    }

    public async Task<OneOf<ApplicationError, Success>> Delete(Guid id)
    {
        var transaction = await context.Transactions.FirstOrDefaultAsync(t => t.Id == id);
        if (transaction is null)
            return new ApplicationError(ErrorTypes.TransactionNotFound, $"Transaction {id} not found.");
        context.Transactions.Remove(transaction);
        await context.SaveChangesAsync();
        return new Success();
    }

    /// <summary>One month of money, bounded in the user's time zone (their 1st, not UTC's).</summary>
    public async Task<OneOf<ApplicationError, MonthlySummaryResponse>> MonthlySummary(
        int year,
        int month,
        AccountService accounts,
        CancellationToken cancellationToken = default
    )
    {
        if (month is < 1 or > 12 || year is < 2000 or > 2100)
            return new ApplicationError(ErrorTypes.ValidationError, "Provide a real year and month.");

        var timeZoneId = (await users.FindAsync(currentUser.UserId!, cancellationToken))?.TimeZoneId
            ?? "UTC";
        if (!TimeZoneInfo.TryFindSystemTimeZoneById(timeZoneId, out var timeZone))
            timeZone = TimeZoneInfo.Utc;

        var startLocal = new DateTime(year, month, 1, 0, 0, 0, DateTimeKind.Unspecified);
        var start = new DateTimeOffset(startLocal, timeZone.GetUtcOffset(startLocal));
        var endLocal = startLocal.AddMonths(1);
        var end = new DateTimeOffset(endLocal, timeZone.GetUtcOffset(endLocal));

        var rows = await context.Transactions
            .Where(t => t.OccurredAt >= start && t.OccurredAt < end && t.Kind != TransactionKind.Transfer)
            .GroupBy(t => new { t.Currency, t.Kind, t.CategoryId })
            .Select(g => new { g.Key.Currency, g.Key.Kind, g.Key.CategoryId, Amount = g.Sum(t => t.Amount) })
            .ToListAsync(cancellationToken);

        var totals = rows
            .GroupBy(r => r.Currency)
            .Select(g => new CurrencyTotals(
                g.Key,
                g.Where(r => r.Kind == TransactionKind.Income).Sum(r => r.Amount),
                g.Where(r => r.Kind == TransactionKind.Expense).Sum(r => r.Amount),
                g.Where(r => r.Kind == TransactionKind.Income).Sum(r => r.Amount)
                    - g.Where(r => r.Kind == TransactionKind.Expense).Sum(r => r.Amount)))
            .OrderBy(t => t.Currency)
            .ToList();

        var categories = await context.Categories.AsNoTracking().Where(c => !c.IsArchived).ToListAsync(cancellationToken);
        var limits = await context.CategoryBudgets.AsNoTracking().ToDictionaryAsync(b => b.CategoryId, cancellationToken);
        var spends = rows
            .Where(r => r.CategoryId is not null)
            .GroupBy(r => new { r.CategoryId, r.Currency })
            .Select(g =>
            {
                var category = categories.FirstOrDefault(c => c.Id == g.Key.CategoryId);
                if (category is null)
                    return null;
                var limit = limits.TryGetValue(category.Id, out var b) && b.Currency == g.Key.Currency
                    ? b.MonthlyLimit
                    : (decimal?)null;
                return new CategorySpend(category.Id, category.Name, category.Kind, category.Icon,
                    g.Key.Currency, g.Sum(r => r.Amount), limit);
            })
            .OfType<CategorySpend>()
            .OrderByDescending(s => s.Amount)
            .ToList();

        var accountList = await accounts.List();
        var baseCurrency = (await context.Profiles.AsNoTracking().FirstOrDefaultAsync(cancellationToken))?.BaseCurrency
            ?? Currency.Htg;
        var rates = await context.CurrencyRates
            .ToDictionaryAsync(r => r.Currency, r => r.RateInBase, cancellationToken);
        return new MonthlySummaryResponse(year, month, timeZone.Id, totals, accountList, spends,
            Combine(baseCurrency, rates, totals, accountList));
    }

    /// <summary>
    /// Everything in the base currency at the user's indicative rates — today's estimate for the
    /// summary header, never applied to stored amounts. A currency with no rate is excluded and
    /// reported in MissingRates rather than silently guessed: unconverted foreign money floats
    /// until the user actually exchanges it (and then the transfer records the real pair).
    /// </summary>
    internal static CombinedEstimate Combine(
        Currency baseCurrency,
        IReadOnlyDictionary<Currency, decimal> rates,
        IReadOnlyList<CurrencyTotals> totals,
        IReadOnlyList<AccountResponse> accounts
    )
    {
        decimal? ToBase(Currency currency, decimal amount) =>
            currency == baseCurrency ? amount
            : rates.TryGetValue(currency, out var rate) ? decimal.Round(amount * rate, 2)
            : null;

        var missing = totals.Select(t => t.Currency)
            .Concat(accounts.Where(a => !a.IsArchived).Select(a => a.Currency))
            .Distinct()
            .Where(c => ToBase(c, 0m) is null)
            .OrderBy(c => c)
            .ToList();

        var income = totals.Sum(t => ToBase(t.Currency, t.Income) ?? 0m);
        var expense = totals.Sum(t => ToBase(t.Currency, t.Expense) ?? 0m);
        var balance = accounts.Where(a => !a.IsArchived).Sum(a => ToBase(a.Currency, a.Balance) ?? 0m);
        return new CombinedEstimate(baseCurrency, income, expense, income - expense, balance, missing);
    }

    internal static TransactionResponse ToResponse(Transaction t) =>
        new(t.Id, t.AccountId, t.Kind, t.Amount, t.Currency, t.OccurredAt, t.CategoryId, t.Note,
            t.TransferAccountId, t.TransferAmount, t.RecurringTransactionId, t.CreatedAt);
}

/// <summary>What a movement, or a rule that books them, refers to: the person's own account, destination and category.</summary>
internal sealed record MovementParts(Account Account, Account? TransferAccount, Category? Category)
{
    public static async Task<OneOf<ApplicationError, MovementParts>> Find(
        BudgetDbContext context,
        Guid accountId,
        Guid? transferAccountId,
        Guid? categoryId
    )
    {
        var account = await context.Accounts.FirstOrDefaultAsync(a => a.Id == accountId);
        if (account is null)
            return new ApplicationError(ErrorTypes.BudgetAccountNotFound, $"Account {accountId} not found.");

        Account? transferAccount = null;
        if (transferAccountId is { } transferId)
        {
            transferAccount = await context.Accounts.FirstOrDefaultAsync(a => a.Id == transferId);
            if (transferAccount is null)
                return new ApplicationError(ErrorTypes.BudgetAccountNotFound, $"Account {transferId} not found.");
        }

        Category? category = null;
        if (categoryId is { } id)
        {
            category = await context.Categories.FirstOrDefaultAsync(c => c.Id == id);
            if (category is null)
                return new ApplicationError(ErrorTypes.BudgetCategoryNotFound, $"Category {id} not found.");
        }
        return new MovementParts(account, transferAccount, category);
    }
}

internal static class TransactionRoutes
{
    extension(IEndpointRouteBuilder routeBuilder)
    {
        public void MapBudgetTransactionRoutes()
        {
            var transactions = routeBuilder.MapGroup("/budget/transactions").WithTags("Budget").RequireAuthorization();

            transactions.MapPost("/", async Task<Results<Ok<TransactionResponse>, ProblemHttpResult>> (CreateTransaction request, TransactionService service, HttpContext context) =>
                    (await service.Create(request)).ToHttp(context))
                .WithName("BudgetTransactionsCreate")
                .WithSummary("Record income, an expense, or a transfer between accounts")
                .ProducesProblem(StatusCodes.Status400BadRequest);

            transactions.MapGet("/", async (TransactionService service, Guid? accountId, Guid? categoryId, TransactionKind? kind, DateTimeOffset? from, DateTimeOffset? to, int page = 1, int pageSize = 50) =>
                    TypedResults.Ok(await service.List(accountId, categoryId, kind, from, to, page, pageSize)))
                .WithName("BudgetTransactionsList")
                .WithSummary("List transactions, newest first (filters: account, category, kind, from/to)");

            transactions.MapGet("/{id:guid}", async Task<Results<Ok<TransactionResponse>, ProblemHttpResult>> (Guid id, TransactionService service, HttpContext context) =>
                    (await service.Get(id)).ToHttp(context))
                .WithName("BudgetTransactionsGet")
                .WithSummary("One transaction (the app opens it to edit it)")
                .ProducesProblem(StatusCodes.Status404NotFound);

            transactions.MapPut("/{id:guid}", async Task<Results<Ok<TransactionResponse>, ProblemHttpResult>> (Guid id, UpdateTransaction request, TransactionService service, HttpContext context) =>
                    (await service.Update(id, request)).ToHttp(context))
                .WithName("BudgetTransactionsUpdate")
                .WithSummary("Edit a transaction: amounts, day, category, note, and its account(s); never its kind")
                .WithDescription("An account left out stays. A transaction may stay on an archived account, never move onto one. "
                    + "One a recurring rule booked changes alone.")
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status404NotFound);

            transactions.MapDelete("/{id:guid}", async Task<Results<Ok<Success>, ProblemHttpResult>> (Guid id, TransactionService service, HttpContext context) =>
                    (await service.Delete(id)).ToHttp(context))
                .WithName("BudgetTransactionsDelete")
                .WithSummary("Delete a transaction")
                .ProducesProblem(StatusCodes.Status404NotFound);

            routeBuilder.MapGet("/budget/summary", async Task<Results<Ok<MonthlySummaryResponse>, ProblemHttpResult>> (int year, int month, TransactionService service, AccountService accounts, HttpContext context, CancellationToken cancellationToken) =>
                    (await service.MonthlySummary(year, month, accounts, cancellationToken)).ToHttp(context))
                .WithTags("Budget")
                .RequireAuthorization()
                .WithName("BudgetMonthlySummary")
                .WithSummary("A month of money in the user's time zone: totals per currency, balances, spending per category vs limits")
                .ProducesProblem(StatusCodes.Status400BadRequest);
        }
    }
}
