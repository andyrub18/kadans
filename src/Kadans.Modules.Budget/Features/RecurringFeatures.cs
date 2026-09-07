using Kadans.Modules.Budget.Contracts;
using Kadans.Modules.Budget.Domain;
using Kadans.Modules.Budget.Persistence;
using Kadans.SharedKernel.Errors;
using Kadans.SharedKernel.Http;
using Kadans.SharedKernel.Recurrence;
using Kadans.SharedKernel.Security;
using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.EntityFrameworkCore;
using OneOf;
using OneOf.Types;
using Quartz;

namespace Kadans.Modules.Budget.Features;

internal sealed class RecurringTransactionService(BudgetDbContext context, ICurrentUserService currentUser)
{
    public async Task<OneOf<ApplicationError, RecurringTransactionResponse>> Create(CreateRecurringTransaction request)
    {
        var userId = currentUser.UserId;
        if (string.IsNullOrWhiteSpace(userId))
            return new ApplicationError(ErrorTypes.Unauthorized, "User is not authenticated.");
        if (request.Kind == TransactionKind.Transfer)
            return new ApplicationError(ErrorTypes.ValidationError, "Recurring transfers are not supported yet.");
        if (Money.ValidateAmount(request.Amount).IsT0)
            return Money.ValidateAmount(request.Amount).AsT0;

        var account = await context.Accounts.FirstOrDefaultAsync(a => a.Id == request.AccountId);
        if (account is null)
            return new ApplicationError(ErrorTypes.BudgetAccountNotFound, $"Account {request.AccountId} not found.");
        if (account.IsArchived)
            return new ApplicationError(ErrorTypes.AccountArchived, "This account is archived.");

        Category? category = null;
        if (request.CategoryId is { } categoryId)
        {
            category = await context.Categories.FirstOrDefaultAsync(c => c.Id == categoryId);
            if (category is null)
                return new ApplicationError(ErrorTypes.BudgetCategoryNotFound, $"Category {categoryId} not found.");
            var expected = request.Kind == TransactionKind.Income ? CategoryKind.Income : CategoryKind.Expense;
            if (category.Kind != expected)
                return new ApplicationError(ErrorTypes.CategoryKindMismatch,
                    $"'{category.Name}' is a {category.Kind} category; this is {request.Kind}.");
        }

        var recurrence = request.Recurrence;
        var schedule = RecurrenceSchedule.Create(
            new RecurrenceSpec(
                recurrence.Frequency,
                recurrence.Interval,
                ByDay: recurrence.ByDayOfWeek,
                ByMonthDay: recurrence.ByMonthDay,
                Count: recurrence.Count,
                Until: recurrence.Until
            ),
            recurrence.StartDate,
            recurrence.TimeZone
        );
        if (schedule.IsT0)
            return schedule.AsT0;

        var rule = new RecurringTransaction
        {
            UserId = userId,
            AccountId = account.Id,
            Kind = request.Kind,
            Amount = request.Amount,
            Currency = account.Currency,
            CategoryId = category?.Id,
            Note = request.Note.Trim(),
            Rrule = schedule.AsT1.Rrule,
            TimeZoneId = schedule.AsT1.TimeZoneId,
            StartDate = schedule.AsT1.Start,
        };
        context.RecurringTransactions.Add(rule);
        await context.SaveChangesAsync();
        return ToResponse(rule);
    }

    public async Task<List<RecurringTransactionResponse>> List() =>
        [.. (await context.RecurringTransactions.OrderBy(r => r.CreatedAt).ToListAsync()).Select(ToResponse)];

    public async Task<OneOf<ApplicationError, RecurringTransactionResponse>> Update(Guid id, UpdateRecurringTransaction request)
    {
        var rule = await context.RecurringTransactions.FirstOrDefaultAsync(r => r.Id == id);
        if (rule is null)
            return new ApplicationError(ErrorTypes.RecurringTransactionNotFound, $"Recurring transaction {id} not found.");
        if (Money.ValidateAmount(request.Amount).IsT0)
            return Money.ValidateAmount(request.Amount).AsT0;

        Category? category = null;
        if (request.CategoryId is { } categoryId)
        {
            category = await context.Categories.FirstOrDefaultAsync(c => c.Id == categoryId);
            if (category is null)
                return new ApplicationError(ErrorTypes.BudgetCategoryNotFound, $"Category {categoryId} not found.");
            var expected = rule.Kind == TransactionKind.Income ? CategoryKind.Income : CategoryKind.Expense;
            if (category.Kind != expected)
                return new ApplicationError(ErrorTypes.CategoryKindMismatch,
                    $"'{category.Name}' is a {category.Kind} category; this is {rule.Kind}.");
        }

        rule.Amount = request.Amount;
        rule.CategoryId = category?.Id;
        rule.Note = request.Note.Trim();
        rule.IsActive = request.IsActive;
        rule.UpdatedAt = DateTimeOffset.UtcNow;
        await context.SaveChangesAsync();
        return ToResponse(rule);
    }

    public async Task<OneOf<ApplicationError, Success>> Delete(Guid id)
    {
        var rule = await context.RecurringTransactions.FirstOrDefaultAsync(r => r.Id == id);
        if (rule is null)
            return new ApplicationError(ErrorTypes.RecurringTransactionNotFound, $"Recurring transaction {id} not found.");
        // Already-materialized transactions stay: they really happened.
        context.RecurringTransactions.Remove(rule);
        await context.SaveChangesAsync();
        return new Success();
    }

    internal static RecurringTransactionResponse ToResponse(RecurringTransaction rule) =>
        new(rule.Id, rule.AccountId, rule.Kind, rule.Amount, rule.Currency, rule.CategoryId, rule.Note,
            rule.Rrule, rule.TimeZoneId, rule.StartDate,
            rule.IsActive ? rule.Schedule.GetNextOccurrence(rule.GeneratedThrough ?? DateTimeOffset.UtcNow) : null,
            rule.IsActive, rule.CreatedAt);
}

/// <summary>
/// Turns due recurring rules into real transactions — salary lands on the 1st whether or not
/// any client is running. Steps on the schedule; overdue rules (downtime) catch up.
/// </summary>
[DisallowConcurrentExecution]
internal sealed class RecurringTransactionJob(BudgetDbContext dbContext, ILogger<RecurringTransactionJob> logger) : IJob
{
    public static readonly JobKey Key = new("recurring-transactions", "budget");

    public async Task Execute(IJobExecutionContext jobContext)
    {
        var now = DateTimeOffset.UtcNow;
        var due = await dbContext.RecurringTransactions
            .IgnoreQueryFilters()
            .Where(r => r.IsActive && (r.GeneratedThrough == null || r.GeneratedThrough < now))
            .Take(500)
            .ToListAsync(jobContext.CancellationToken);

        var created = 0;
        foreach (var rule in due)
        {
            foreach (var occurredAt in rule.DueOccurrences(now))
            {
                dbContext.Transactions.Add(new Transaction
                {
                    UserId = rule.UserId,
                    AccountId = rule.AccountId,
                    Kind = rule.Kind,
                    Amount = rule.Amount,
                    Currency = rule.Currency,
                    OccurredAt = occurredAt,
                    CategoryId = rule.CategoryId,
                    Note = rule.Note,
                    RecurringTransactionId = rule.Id,
                });
                created++;
            }
            rule.GeneratedThrough = now;
            if (rule.IsExhaustedAfter(now))
                rule.IsActive = false;
        }

        if (created > 0)
        {
            await dbContext.SaveChangesAsync(jobContext.CancellationToken);
            logger.LogInformation("Recurring budget rules materialized {Count} transaction(s)", created);
        }
        else if (due.Count > 0)
        {
            await dbContext.SaveChangesAsync(jobContext.CancellationToken);
        }
    }
}

internal static class RecurringRoutes
{
    extension(IEndpointRouteBuilder routeBuilder)
    {
        public void MapBudgetRecurringRoutes()
        {
            var recurring = routeBuilder.MapGroup("/budget/recurring").WithTags("Budget").RequireAuthorization();

            recurring.MapPost("/", async Task<Results<Ok<RecurringTransactionResponse>, ProblemHttpResult>> (CreateRecurringTransaction request, RecurringTransactionService service, HttpContext context) =>
                    (await service.Create(request)).ToHttp(context))
                .WithName("BudgetRecurringCreate")
                .WithSummary("A recurring income/expense (salary on the 1st, rent monthly…) on the shared recurrence engine")
                .ProducesProblem(StatusCodes.Status400BadRequest);

            recurring.MapGet("/", async (RecurringTransactionService service) => TypedResults.Ok(await service.List()))
                .WithName("BudgetRecurringList")
                .WithSummary("List recurring rules with their next occurrence");

            recurring.MapPut("/{id:guid}", async Task<Results<Ok<RecurringTransactionResponse>, ProblemHttpResult>> (Guid id, UpdateRecurringTransaction request, RecurringTransactionService service, HttpContext context) =>
                    (await service.Update(id, request)).ToHttp(context))
                .WithName("BudgetRecurringUpdate")
                .WithSummary("Change amount/category/note, or pause (isActive: false)")
                .ProducesProblem(StatusCodes.Status404NotFound);

            recurring.MapDelete("/{id:guid}", async Task<Results<Ok<Success>, ProblemHttpResult>> (Guid id, RecurringTransactionService service, HttpContext context) =>
                    (await service.Delete(id)).ToHttp(context))
                .WithName("BudgetRecurringDelete")
                .WithSummary("Delete the rule (already-created transactions stay)")
                .ProducesProblem(StatusCodes.Status404NotFound);
        }
    }
}
