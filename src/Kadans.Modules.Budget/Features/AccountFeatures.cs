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

internal sealed class AccountService(BudgetDbContext context, ICurrentUserService currentUser)
{
    public async Task<OneOf<ApplicationError, AccountResponse>> Create(CreateAccount request)
    {
        var userId = currentUser.UserId;
        if (string.IsNullOrWhiteSpace(userId))
            return new ApplicationError(ErrorTypes.Unauthorized, "User is not authenticated.");
        if (string.IsNullOrWhiteSpace(request.Name))
            return new ApplicationError(ErrorTypes.ValidationError, "Account name is required.");
        if (request.InitialBalance != decimal.Round(request.InitialBalance, 2))
            return new ApplicationError(ErrorTypes.InvalidAmount, "Initial balance can have at most two decimals.");

        var account = new Account
        {
            UserId = userId,
            Name = request.Name.Trim(),
            Currency = request.Currency,
            Type = request.Type,
            InitialBalance = request.InitialBalance,
        };
        context.Accounts.Add(account);
        await context.SaveChangesAsync();
        return ToResponse(account, account.InitialBalance);
    }

    public async Task<OneOf<ApplicationError, AccountResponse>> Update(Guid id, UpdateAccount request)
    {
        var account = await context.Accounts.FirstOrDefaultAsync(a => a.Id == id);
        if (account is null)
            return new ApplicationError(ErrorTypes.BudgetAccountNotFound, $"Account {id} not found.");
        if (string.IsNullOrWhiteSpace(request.Name))
            return new ApplicationError(ErrorTypes.ValidationError, "Account name is required.");

        account.Name = request.Name.Trim();
        account.Type = request.Type;
        account.IsArchived = request.IsArchived;
        account.UpdatedAt = DateTimeOffset.UtcNow;
        await context.SaveChangesAsync();
        return ToResponse(account, await BalanceOf(account));
    }

    public async Task<List<AccountResponse>> List(bool includeArchived = false)
    {
        var accounts = await context.Accounts
            .Where(a => includeArchived || !a.IsArchived)
            .OrderBy(a => a.CreatedAt)
            .ToListAsync();
        var effects = await BalanceEffects(accounts.Select(a => a.Id).ToList());
        return [.. accounts.Select(a => ToResponse(a, a.InitialBalance + effects.GetValueOrDefault(a.Id)))];
    }

    /// <summary>Balance = initial + everything in − everything out, all computed in the database.</summary>
    private async Task<decimal> BalanceOf(Account account) =>
        account.InitialBalance + (await BalanceEffects([account.Id])).GetValueOrDefault(account.Id);

    private async Task<Dictionary<Guid, decimal>> BalanceEffects(List<Guid> accountIds)
    {
        var own = await context.Transactions
            .Where(t => accountIds.Contains(t.AccountId))
            .GroupBy(t => t.AccountId)
            .Select(g => new
            {
                AccountId = g.Key,
                Effect = g.Sum(t =>
                    t.Kind == TransactionKind.Income ? t.Amount : -t.Amount),
            })
            .ToListAsync();

        var received = await context.Transactions
            .Where(t => t.TransferAccountId != null && accountIds.Contains(t.TransferAccountId.Value))
            .GroupBy(t => t.TransferAccountId!.Value)
            .Select(g => new { AccountId = g.Key, Effect = g.Sum(t => t.TransferAmount ?? 0m) })
            .ToListAsync();

        return own.Concat(received)
            .GroupBy(e => e.AccountId)
            .ToDictionary(g => g.Key, g => g.Sum(e => e.Effect));
    }

    internal static AccountResponse ToResponse(Account account, decimal balance) =>
        new(account.Id, account.Name, account.Currency, account.Type, account.InitialBalance,
            balance, account.IsArchived, account.CreatedAt, account.UpdatedAt);
}

internal static class AccountRoutes
{
    extension(IEndpointRouteBuilder routeBuilder)
    {
        public void MapBudgetAccountRoutes()
        {
            var accounts = routeBuilder.MapGroup("/budget/accounts").WithTags("Budget").RequireAuthorization();

            accounts.MapPost("/", async Task<Results<Ok<AccountResponse>, ProblemHttpResult>> (CreateAccount request, AccountService service, HttpContext context) =>
                    (await service.Create(request)).ToHttp(context))
                .WithName("BudgetAccountsCreate")
                .WithSummary("Create an account (one currency each: HTG or USD)")
                .ProducesProblem(StatusCodes.Status400BadRequest);

            accounts.MapGet("/", async (AccountService service, bool includeArchived = false) =>
                    TypedResults.Ok(await service.List(includeArchived)))
                .WithName("BudgetAccountsList")
                .WithSummary("List accounts with computed balances");

            accounts.MapPut("/{id:guid}", async Task<Results<Ok<AccountResponse>, ProblemHttpResult>> (Guid id, UpdateAccount request, AccountService service, HttpContext context) =>
                    (await service.Update(id, request)).ToHttp(context))
                .WithName("BudgetAccountsUpdate")
                .WithSummary("Rename, retype or archive an account")
                .ProducesProblem(StatusCodes.Status404NotFound);
        }
    }
}
