using Kadans.Modules.Budget.Contracts;
using Kadans.Modules.Budget.Domain;
using Kadans.Modules.Budget.Persistence;
using Kadans.SharedKernel.Errors;
using Kadans.SharedKernel.Http;
using Kadans.SharedKernel.Security;
using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.EntityFrameworkCore;
using OneOf;
using OneOf.Types;

namespace Kadans.Modules.Budget.Features;

internal sealed class CategoryService(BudgetDbContext context, ICurrentUserService currentUser)
{
    public async Task<OneOf<ApplicationError, CategoryResponse>> Create(CreateCategory request)
    {
        var userId = currentUser.UserId;
        if (string.IsNullOrWhiteSpace(userId))
            return new ApplicationError(ErrorTypes.Unauthorized, "User is not authenticated.");
        if (string.IsNullOrWhiteSpace(request.Name))
            return new ApplicationError(ErrorTypes.ValidationError, "Category name is required.");

        var category = new Category
        {
            UserId = userId,
            Name = request.Name.Trim(),
            Kind = request.Kind,
            Icon = request.Icon?.Trim(),
        };
        context.Categories.Add(category);
        await context.SaveChangesAsync();
        return ToResponse(category);
    }

    public async Task<OneOf<ApplicationError, CategoryResponse>> Update(Guid id, UpdateCategory request)
    {
        var category = await context.Categories.FirstOrDefaultAsync(c => c.Id == id);
        if (category is null)
            return new ApplicationError(ErrorTypes.BudgetCategoryNotFound, $"Category {id} not found.");
        if (string.IsNullOrWhiteSpace(request.Name))
            return new ApplicationError(ErrorTypes.ValidationError, "Category name is required.");

        category.Name = request.Name.Trim();
        category.Icon = request.Icon?.Trim();
        category.IsArchived = request.IsArchived;
        category.UpdatedAt = DateTimeOffset.UtcNow;
        await context.SaveChangesAsync();
        return ToResponse(category);
    }

    public async Task<List<CategoryResponse>> List(bool includeArchived = false) =>
        [.. (await context.Categories
            .Where(c => includeArchived || !c.IsArchived)
            .OrderBy(c => c.Kind).ThenBy(c => c.Name)
            .ToListAsync()).Select(ToResponse)];

    public async Task<OneOf<ApplicationError, CategoryLimitResponse>> SetLimit(Guid categoryId, SetCategoryLimit request)
    {
        var userId = currentUser.UserId!;
        var category = await context.Categories.FirstOrDefaultAsync(c => c.Id == categoryId);
        if (category is null)
            return new ApplicationError(ErrorTypes.BudgetCategoryNotFound, $"Category {categoryId} not found.");
        if (category.Kind != CategoryKind.Expense)
            return new ApplicationError(ErrorTypes.CategoryKindMismatch, "Budgets apply to expense categories.");
        if (Money.ValidateAmount(request.Amount).IsT0)
            return Money.ValidateAmount(request.Amount).AsT0;

        var limit = await context.CategoryBudgets.FirstOrDefaultAsync(b => b.CategoryId == categoryId);
        if (limit is null)
        {
            limit = new CategoryBudget
            {
                UserId = userId,
                CategoryId = categoryId,
                Currency = request.Currency,
                Amount = request.Amount,
                Period = request.Period,
            };
            context.CategoryBudgets.Add(limit);
        }
        else if (limit.Currency != request.Currency)
        {
            return new ApplicationError(ErrorTypes.CurrencyMismatch,
                "A limit keeps its currency: remove it to set one in another currency.");
        }
        else
        {
            limit.Change(request.Amount, request.Period, DateTimeOffset.UtcNow);
        }
        await context.SaveChangesAsync();
        return new CategoryLimitResponse(categoryId, limit.Amount, limit.Currency, limit.Period);
    }

    public async Task<OneOf<ApplicationError, Success>> RemoveBudget(Guid categoryId)
    {
        var budget = await context.CategoryBudgets.FirstOrDefaultAsync(b => b.CategoryId == categoryId);
        if (budget is null)
            return new ApplicationError(ErrorTypes.BudgetCategoryNotFound, $"No budget on category {categoryId}.");
        context.CategoryBudgets.Remove(budget);
        await context.SaveChangesAsync();
        return new Success();
    }

    internal static CategoryResponse ToResponse(Category category) =>
        new(category.Id, category.Name, category.Kind, category.Icon, category.IsArchived);
}

internal static class CategoryRoutes
{
    extension(IEndpointRouteBuilder routeBuilder)
    {
        public void MapBudgetCategoryRoutes()
        {
            var categories = routeBuilder.MapGroup("/budget/categories").WithTags("Budget").RequireAuthorization();

            categories.MapPost("/", async Task<Results<Ok<CategoryResponse>, ProblemHttpResult>> (CreateCategory request, CategoryService service, HttpContext context) =>
                    (await service.Create(request)).ToHttp(context))
                .WithName("BudgetCategoriesCreate")
                .WithSummary("Create an income or expense category")
                .ProducesProblem(StatusCodes.Status400BadRequest);

            categories.MapGet("/", async (CategoryService service, bool includeArchived = false) =>
                    TypedResults.Ok(await service.List(includeArchived)))
                .WithName("BudgetCategoriesList")
                .WithSummary("List categories");

            categories.MapPut("/{id:guid}", async Task<Results<Ok<CategoryResponse>, ProblemHttpResult>> (Guid id, UpdateCategory request, CategoryService service, HttpContext context) =>
                    (await service.Update(id, request)).ToHttp(context))
                .WithName("BudgetCategoriesUpdate")
                .WithSummary("Rename, re-icon or archive a category")
                .ProducesProblem(StatusCodes.Status404NotFound);

            categories.MapPut("/{id:guid}/budget", async Task<Results<Ok<CategoryLimitResponse>, ProblemHttpResult>> (Guid id, SetCategoryLimit request, CategoryService service, HttpContext context) =>
                    (await service.SetLimit(id, request)).ToHttp(context))
                .WithName("BudgetCategoriesSetBudget")
                .WithSummary("Set an expense category's limit (week, month or year), or change its amount and period")
                .WithDescription("One limit per category, in the currency it was set in: spending in other currencies counts at the "
                    + "person's indicative rates. Another currency is refused: remove the limit first. Alerts go out at 80% and 100%.")
                .ProducesProblem(StatusCodes.Status400BadRequest);

            categories.MapDelete("/{id:guid}/budget", async Task<Results<Ok<Success>, ProblemHttpResult>> (Guid id, CategoryService service, HttpContext context) =>
                    (await service.RemoveBudget(id)).ToHttp(context))
                .WithName("BudgetCategoriesRemoveBudget")
                .WithSummary("Remove the category's limit")
                .ProducesProblem(StatusCodes.Status404NotFound);
        }
    }
}
