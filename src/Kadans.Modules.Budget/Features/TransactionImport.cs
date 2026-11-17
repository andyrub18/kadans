using Kadans.Modules.Budget.Contracts;
using Kadans.Modules.Budget.Domain;
using Kadans.Modules.Budget.Persistence;
using Kadans.SharedKernel.Errors;
using Kadans.SharedKernel.Http;
using Kadans.SharedKernel.Security;
using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.EntityFrameworkCore;
using OneOf;

namespace Kadans.Modules.Budget.Features;

/// <summary>
/// A bank statement's lines, booked as movements (ARCHITECTURE → "Statements in, data out"). The app reads the file
/// (CSV, OFX) on the person's device and sends only the lines they kept: the statement itself, with whatever else the
/// bank put in it, never reaches the server. Each line carries a key that names it, and an account books a key once:
/// importing the same file again, or the next statement that overlaps it, adds nothing twice.
/// </summary>
internal sealed class TransactionImportService(BudgetDbContext context, ICurrentUserService currentUser, CategoryLimitAlerts limitAlerts)
{
    /// <summary>The most lines one request books: a year of a busy account comes in a handful of requests.</summary>
    public const int MaxPerRequest = 500;

    /// <summary>The most keys handed to the app for one account, its newest: years of statements.</summary>
    public const int MaxKnownKeys = 50_000;

    /// <summary>The most texts handed over with their category, and how many of the newest movements are looked at for them.</summary>
    public const int MaxKnownTexts = 5_000;
    private const int MovementsRead = 20_000;

    public async Task<OneOf<ApplicationError, ImportTransactionsResponse>> Import(ImportTransactions request)
    {
        var userId = currentUser.UserId;
        if (string.IsNullOrWhiteSpace(userId))
            return new ApplicationError(ErrorTypes.Unauthorized, "User is not authenticated.");
        if (request.Movements is not { Count: > 0 })
            return new ApplicationError(ErrorTypes.ValidationError, "There is nothing to import.");
        if (request.Movements.Count > MaxPerRequest)
            return new ApplicationError(ErrorTypes.ValidationError, "At most 500 lines can be imported at once.");
        if (request.Movements.Any(m => !Transaction.IsImportKey(m.ImportKey)))
            return new ApplicationError(ErrorTypes.ValidationError, "A statement's line is missing what names it.");
        if (request.Movements.Any(m => m.Kind == TransactionKind.Transfer))
            return new ApplicationError(ErrorTypes.ValidationError, "A statement's lines are income or expenses, not transfers.");

        var account = await context.Accounts.FirstOrDefaultAsync(a => a.Id == request.AccountId);
        if (account is null)
            return new ApplicationError(ErrorTypes.BudgetAccountNotFound, $"Account {request.AccountId} not found.");

        var categoryIds = request.Movements.Where(m => m.CategoryId is not null).Select(m => m.CategoryId!.Value).Distinct().ToList();
        var categories = await context.Categories.Where(c => categoryIds.Contains(c.Id)).ToDictionaryAsync(c => c.Id);
        if (categoryIds.FirstOrDefault(id => !categories.ContainsKey(id)) is var missing && missing != Guid.Empty)
            return new ApplicationError(ErrorTypes.BudgetCategoryNotFound, $"Category {missing} not found.");

        var keys = request.Movements.Select(m => m.ImportKey).Distinct().ToList();
        var booked = (await context.Transactions.Where(t => t.AccountId == account.Id && t.ImportKey != null && keys.Contains(t.ImportKey))
            .Select(t => t.ImportKey!)
            .ToListAsync()).ToHashSet(StringComparer.Ordinal);

        var skipped = 0;
        var fresh = new List<Transaction>();
        foreach (var movement in request.Movements)
        {
            // Booked before, or twice in this very request: once is enough.
            if (!booked.Add(movement.ImportKey))
            {
                skipped++;
                continue;
            }
            var category = movement.CategoryId is { } id ? categories[id] : null;
            var created = Transaction.Create(
                userId, account, movement.Kind, movement.Amount, movement.OccurredAt, category, movement.Note ?? "",
                transferAccount: null, transferAmount: null, importKey: movement.ImportKey
            );
            // One line that cannot be booked stops them all: half a statement would be harder to put right than none.
            if (created.IsT0)
                return created.AsT0;
            fresh.Add(created.AsT1);
        }

        context.Transactions.AddRange(fresh);
        try
        {
            await context.SaveChangesAsync();
        }
        catch (DbUpdateException)
        {
            // The same statement sent twice at the same moment (two devices, a double tap): the index let one through.
            return new ApplicationError(ErrorTypes.ValidationError, "This statement is being imported from somewhere else. Try again in a moment.");
        }

        var limited = fresh.Where(t => t is { Kind: TransactionKind.Expense, CategoryId: not null }).Select(t => t.CategoryId!.Value).Distinct().ToList();
        if (limited.Count > 0)
            await limitAlerts.CheckAsync(userId, limited, DateTimeOffset.UtcNow);
        return new ImportTransactionsResponse(fresh.Count, skipped);
    }

    /// <summary>
    /// What the app sorts a statement's lines with, on the device: the keys the account already has (those lines are
    /// shown set aside), and the category of the person's latest movement of each kind and text (the same shop, the
    /// same salary). Asked before anything of the statement is sent, so nothing of a line the person does not keep
    /// ever comes here: the app compares, the server only says what it already holds.
    /// </summary>
    public async Task<OneOf<ApplicationError, ImportKnownResponse>> Known(Guid accountId)
    {
        if (string.IsNullOrWhiteSpace(currentUser.UserId))
            return new ApplicationError(ErrorTypes.Unauthorized, "User is not authenticated.");
        if (!await context.Accounts.AnyAsync(a => a.Id == accountId))
            return new ApplicationError(ErrorTypes.BudgetAccountNotFound, $"Account {accountId} not found.");

        var keys = await context.Transactions
            .Where(t => t.AccountId == accountId && t.ImportKey != null)
            .OrderByDescending(t => t.CreatedAt)
            .Select(t => t.ImportKey!)
            .Take(MaxKnownKeys)
            .ToListAsync();

        // Categories the person still uses; the newest movement of each text wins.
        var usable = (await context.Categories.Where(c => !c.IsArchived).Select(c => c.Id).ToListAsync()).ToHashSet();
        var filed = await context.Transactions
            .Where(t => t.CategoryId != null && t.Kind != TransactionKind.Transfer && t.Note != "")
            .OrderByDescending(t => t.OccurredAt)
            .Select(t => new { t.Kind, t.Note, CategoryId = t.CategoryId!.Value })
            .Take(MovementsRead)
            .ToListAsync();
        var suggested = new Dictionary<(TransactionKind, string), Guid>();
        foreach (var movement in filed)
        {
            if (suggested.Count >= MaxKnownTexts)
                break;
            if (usable.Contains(movement.CategoryId))
                suggested.TryAdd((movement.Kind, movement.Note), movement.CategoryId);
        }
        return new ImportKnownResponse(keys, [.. suggested.Select(s => new SuggestedCategory(s.Key.Item1, s.Key.Item2, s.Value))]);
    }
}

internal static class TransactionImportRoutes
{
    extension(IEndpointRouteBuilder routeBuilder)
    {
        public void MapBudgetImportRoutes()
        {
            var import = routeBuilder.MapGroup("/budget/transactions/import").WithTags("Budget").RequireAuthorization();

            import.MapPost("/", async Task<Results<Ok<ImportTransactionsResponse>, ProblemHttpResult>> (ImportTransactions request, TransactionImportService service, HttpContext context) =>
                    (await service.Import(request)).ToHttp(context))
                .WithName("BudgetTransactionsImport")
                .WithSummary("Book a bank statement's lines on an account")
                .WithDescription("Up to 500 lines, income or expenses, booked together or not at all. Each carries an import key that names the line; a key the account already has is skipped, so a statement imported twice adds nothing twice. The app reads the statement itself; only the lines the person kept are sent.")
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status404NotFound);

            import.MapGet("/known", async Task<Results<Ok<ImportKnownResponse>, ProblemHttpResult>> (Guid accountId, TransactionImportService service, HttpContext context) =>
                    (await service.Known(accountId)).ToHttp(context))
                .WithName("BudgetTransactionsImportKnown")
                .WithSummary("What an account already has of its statements, and the person's texts with their categories")
                .WithDescription("Books nothing and takes nothing of a statement. The import keys the account already has (its newest 50,000), and for the person's latest movements the category of each kind and text (5,000 at most). The app sorts a statement's lines with them on the device: the ones already there, a category for the others.")
                .ProducesProblem(StatusCodes.Status404NotFound);
        }
    }
}
