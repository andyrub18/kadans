using Kadans.Modules.Billing.Features;
using Kadans.Modules.Billing.Google;
using Kadans.Modules.Billing.Persistence;
using Kadans.SharedKernel.Modules;
using Kadans.SharedKernel.Persistence;
using Kadans.SharedKernel.Users;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Quartz;

namespace Kadans.Modules.Billing;

/// <summary>
/// Subscriptions: the phone apps need one, the desktop app is free (ARCHITECTURE → Subscriptions). The stores sell
/// and bill; Kadans keeps what they report per account and answers <see cref="IMobileAccess"/>.
/// </summary>
public sealed class BillingModule : IModule
{
    private BillingOptions options = new();

    public void AddServices(IServiceCollection services, IConfiguration configuration)
    {
        var connectionString = KadansDatabase.ConnectionString(configuration);
        services.AddDbContext<BillingDbContext>(db =>
            db.UseNpgsql(
                connectionString,
                npgsql => npgsql.MigrationsHistoryTable("__ef_migrations_history", BillingDbContext.Schema)
            ),
            optionsLifetime: ServiceLifetime.Singleton // built once: the options depend on configuration only
        );

        var section = configuration.GetSection(BillingOptions.SectionName);
        services.Configure<BillingOptions>(section);
        options = section.Get<BillingOptions>() ?? new BillingOptions();

        services.TryAddSingleton(TimeProvider.System);
        services.AddSingleton<IGooglePlay, GooglePlayApi>();
        services.AddSingleton<IPushAuthenticator, PubSubPushAuthenticator>();
        services.AddSingleton<MobileAccess>();
        services.AddSingleton<IMobileAccess>(provider => provider.GetRequiredService<MobileAccess>());
        services.AddScoped<Subscriptions>();
        services.AddScoped<FreeAccounts>();
        services.AddScoped<IUserDataEraser, BillingUserDataEraser>();

        services.AddQuartz(quartz =>
        {
            quartz.AddJob<SubscriptionReconcileJob>(job => job.WithIdentity(SubscriptionReconcileJob.Key));
            quartz.AddTrigger(trigger =>
                trigger
                    .ForJob(SubscriptionReconcileJob.Key)
                    .WithIdentity("subscription-reconcile-trigger", "billing")
                    .StartAt(DateBuilder.FutureDate(3, IntervalUnit.Minute))
                    .WithSimpleSchedule(s => s.WithIntervalInHours(1).RepeatForever())
            );
        });
    }

    public void MapEndpoints(IEndpointRouteBuilder endpoints) => endpoints.MapBillingRoutes(options);

    public Task InitializeAsync(IServiceProvider services, CancellationToken cancellationToken) =>
        services.MigrateIfConfiguredAsync<BillingDbContext>(cancellationToken);
}
