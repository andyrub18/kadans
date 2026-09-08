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

internal sealed class BudgetSettingsService(BudgetDbContext context, ICurrentUserService currentUser)
{
    public async Task<BudgetSettingsResponse> Get()
    {
        var profile = await context.Profiles.FirstOrDefaultAsync();
        var rates = await context.CurrencyRates.OrderBy(r => r.Currency).ToListAsync();
        return new BudgetSettingsResponse(
            profile?.BaseCurrency ?? Currency.Htg,
            [.. rates.Select(r => new CurrencyRateResponse(r.Currency, r.RateInBase, r.UpdatedAt))]
        );
    }

    public async Task<OneOf<ApplicationError, BudgetSettingsResponse>> SetBaseCurrency(SetBaseCurrency request)
    {
        var userId = currentUser.UserId;
        if (string.IsNullOrWhiteSpace(userId))
            return new ApplicationError(ErrorTypes.Unauthorized, "User is not authenticated.");

        var profile = await context.Profiles.FirstOrDefaultAsync();
        var previousBase = profile?.BaseCurrency ?? Currency.Htg; // no row yet = the implicit default
        if (profile is null)
        {
            profile = new BudgetProfile { UserId = userId, BaseCurrency = request.BaseCurrency };
            context.Profiles.Add(profile);
        }
        else
        {
            profile.BaseCurrency = request.BaseCurrency;
            profile.UpdatedAt = DateTimeOffset.UtcNow;
        }
        // Stored rates were denominated in the previous base; keeping them would be silent lies.
        if (previousBase != request.BaseCurrency)
            context.CurrencyRates.RemoveRange(await context.CurrencyRates.ToListAsync());
        await context.SaveChangesAsync();
        return await Get();
    }

    public async Task<OneOf<ApplicationError, BudgetSettingsResponse>> SetRate(Currency currency, SetCurrencyRate request)
    {
        var userId = currentUser.UserId;
        if (string.IsNullOrWhiteSpace(userId))
            return new ApplicationError(ErrorTypes.Unauthorized, "User is not authenticated.");

        var baseCurrency = (await context.Profiles.FirstOrDefaultAsync())?.BaseCurrency ?? Currency.Htg;
        if (currency == baseCurrency)
            return new ApplicationError(ErrorTypes.CurrencyMismatch, "The base currency needs no rate — it is worth 1 of itself.");
        if (request.RateInBase <= 0 || request.RateInBase > 100_000_000m)
            return new ApplicationError(ErrorTypes.InvalidAmount, "The rate must be positive.");
        if (request.RateInBase != decimal.Round(request.RateInBase, 6))
            return new ApplicationError(ErrorTypes.InvalidAmount, "The rate can have at most six decimals.");

        var rate = await context.CurrencyRates.FirstOrDefaultAsync(r => r.Currency == currency);
        if (rate is null)
        {
            context.CurrencyRates.Add(new CurrencyRate { UserId = userId, Currency = currency, RateInBase = request.RateInBase });
        }
        else
        {
            rate.RateInBase = request.RateInBase;
            rate.UpdatedAt = DateTimeOffset.UtcNow;
        }
        await context.SaveChangesAsync();
        return await Get();
    }

    public async Task<OneOf<ApplicationError, Success>> DeleteRate(Currency currency)
    {
        var rate = await context.CurrencyRates.FirstOrDefaultAsync(r => r.Currency == currency);
        if (rate is null)
            return new ApplicationError(ErrorTypes.BudgetAccountNotFound, $"No rate stored for {currency}.");
        context.CurrencyRates.Remove(rate);
        await context.SaveChangesAsync();
        return new Success();
    }
}

internal static class BudgetSettingsRoutes
{
    extension(IEndpointRouteBuilder routeBuilder)
    {
        public void MapBudgetSettingsRoutes()
        {
            var settings = routeBuilder.MapGroup("/budget/settings").WithTags("Budget").RequireAuthorization();

            settings.MapGet("/", async (BudgetSettingsService service) => TypedResults.Ok(await service.Get()))
                .WithName("BudgetSettingsGet")
                .WithSummary("Base currency and the indicative rates (1 foreign unit = X base)");

            settings.MapPut("/base-currency", async Task<Results<Ok<BudgetSettingsResponse>, ProblemHttpResult>> (SetBaseCurrency request, BudgetSettingsService service, HttpContext context) =>
                    (await service.SetBaseCurrency(request)).ToHttp(context))
                .WithName("BudgetSettingsSetBase")
                .WithSummary("Set the day-to-day currency estimates are expressed in (clears stored rates)")
                .ProducesProblem(StatusCodes.Status400BadRequest);

            settings.MapPut("/rates/{currency}", async Task<Results<Ok<BudgetSettingsResponse>, ProblemHttpResult>> (Currency currency, SetCurrencyRate request, BudgetSettingsService service, HttpContext context) =>
                    (await service.SetRate(currency, request)).ToHttp(context))
                .WithName("BudgetSettingsSetRate")
                .WithSummary("Update today's indicative rate for one currency — estimates and pre-fill only, recorded amounts never move")
                .ProducesProblem(StatusCodes.Status400BadRequest);

            settings.MapDelete("/rates/{currency}", async Task<Results<Ok<Success>, ProblemHttpResult>> (Currency currency, BudgetSettingsService service, HttpContext context) =>
                    (await service.DeleteRate(currency)).ToHttp(context))
                .WithName("BudgetSettingsDeleteRate")
                .WithSummary("Forget the stored rate for one currency")
                .ProducesProblem(StatusCodes.Status404NotFound);
        }
    }
}
