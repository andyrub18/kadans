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

internal sealed class ExchangeRateService(BudgetDbContext context, ICurrentUserService currentUser)
{
    public async Task<ExchangeRateResponse> Get()
    {
        var setting = await context.ExchangeRates.FirstOrDefaultAsync();
        return new ExchangeRateResponse(setting?.HtgPerUsd, setting?.UpdatedAt);
    }

    public async Task<OneOf<ApplicationError, ExchangeRateResponse>> Set(SetExchangeRate request)
    {
        var userId = currentUser.UserId;
        if (string.IsNullOrWhiteSpace(userId))
            return new ApplicationError(ErrorTypes.Unauthorized, "User is not authenticated.");
        if (request.HtgPerUsd <= 0 || request.HtgPerUsd > 100_000m)
            return new ApplicationError(ErrorTypes.InvalidAmount, "The rate must be a positive HTG-per-USD number.");
        if (request.HtgPerUsd != decimal.Round(request.HtgPerUsd, 4))
            return new ApplicationError(ErrorTypes.InvalidAmount, "The rate can have at most four decimals.");

        var setting = await context.ExchangeRates.FirstOrDefaultAsync();
        if (setting is null)
        {
            setting = new ExchangeRateSetting { UserId = userId, HtgPerUsd = request.HtgPerUsd };
            context.ExchangeRates.Add(setting);
        }
        else
        {
            setting.HtgPerUsd = request.HtgPerUsd;
            setting.UpdatedAt = DateTimeOffset.UtcNow;
        }
        await context.SaveChangesAsync();
        return new ExchangeRateResponse(setting.HtgPerUsd, setting.UpdatedAt);
    }
}

internal static class ExchangeRateRoutes
{
    extension(IEndpointRouteBuilder routeBuilder)
    {
        public void MapBudgetExchangeRateRoutes()
        {
            var rate = routeBuilder.MapGroup("/budget/exchange-rate").WithTags("Budget").RequireAuthorization();

            rate.MapGet("/", async (ExchangeRateService service) => TypedResults.Ok(await service.Get()))
                .WithName("BudgetExchangeRateGet")
                .WithSummary("The user's own HTG-per-USD rate (null until set)");

            rate.MapPut("/", async Task<Results<Ok<ExchangeRateResponse>, ProblemHttpResult>> (SetExchangeRate request, ExchangeRateService service, HttpContext context) =>
                    (await service.Set(request)).ToHttp(context))
                .WithName("BudgetExchangeRateSet")
                .WithSummary("Update the rate (it changes daily — estimates and transfer pre-fill only, stored amounts never move)")
                .ProducesProblem(StatusCodes.Status400BadRequest);
        }
    }
}
