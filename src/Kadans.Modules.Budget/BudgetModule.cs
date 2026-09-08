using Kadans.Modules.Budget.Features;
using Kadans.Modules.Budget.Persistence;
using Kadans.SharedKernel.Modules;
using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Quartz;

namespace Kadans.Modules.Budget;

/// <summary>Accounts, transactions, budgets and recurring money (HTG/USD). Owns the <c>budget</c> schema.</summary>
public sealed class BudgetModule : IModule
{
    public void AddServices(IServiceCollection services, IConfiguration configuration)
    {
        services.AddDbContext<BudgetDbContext>(options =>
            options.UseNpgsql(
                configuration.GetConnectionString("kadans"),
                npgsql => npgsql.MigrationsHistoryTable("__ef_migrations_history", BudgetDbContext.Schema)
            )
        );

        var budgetSection = configuration.GetSection(BudgetOptions.SectionName);
        services.Configure<BudgetOptions>(budgetSection);
        var budgetOptions = budgetSection.Get<BudgetOptions>() ?? new BudgetOptions();

        services.AddQuartz(quartz =>
        {
            quartz.AddJob<RecurringTransactionJob>(job => job.WithIdentity(RecurringTransactionJob.Key));
            quartz.AddTrigger(trigger =>
                trigger
                    .ForJob(RecurringTransactionJob.Key)
                    .WithIdentity("recurring-transactions-trigger", "budget")
                    .StartAt(DateBuilder.FutureDate(15, IntervalUnit.Second))
                    .WithSimpleSchedule(s => s.WithIntervalInMinutes(Math.Max(1, budgetOptions.RecurringIntervalMinutes)).RepeatForever())
            );
        });

        services.AddScoped<AccountService>();
        services.AddScoped<CategoryService>();
        services.AddScoped<TransactionService>();
        services.AddScoped<RecurringTransactionService>();
        services.AddScoped<BudgetSettingsService>();
    }

    public void MapEndpoints(IEndpointRouteBuilder endpoints)
    {
        endpoints.MapBudgetAccountRoutes();
        endpoints.MapBudgetCategoryRoutes();
        endpoints.MapBudgetTransactionRoutes();
        endpoints.MapBudgetRecurringRoutes();
        endpoints.MapBudgetSettingsRoutes();
    }
}

internal sealed class BudgetOptions
{
    public const string SectionName = "Budget";

    /// <summary>How often due recurring rules are turned into transactions.</summary>
    public int RecurringIntervalMinutes { get; set; } = 15;
}
