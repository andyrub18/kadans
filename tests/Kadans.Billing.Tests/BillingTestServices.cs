using Kadans.Modules.Billing;
using Kadans.Modules.Billing.Features;
using Kadans.Modules.Billing.Google;
using Kadans.Modules.Billing.Persistence;
using Kadans.SharedKernel.Security;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace Kadans.Billing.Tests;

/// <summary>The Billing module's services over in-memory SQLite, with Google, the clock and the user as test doubles.</summary>
internal static class BillingTestServices
{
    public static ServiceProvider Build(SqliteConnection connection, Action<BillingOptions>? configure = null)
    {
        var collection = new ServiceCollection();
        collection.AddLogging();
        collection.AddDbContext<BillingDbContext>(options => options.UseSqlite(connection));
        collection.Configure<BillingOptions>(options =>
        {
            options.Required = true;
            configure?.Invoke(options);
        });
        collection.AddSingleton<ManualClock>();
        collection.AddSingleton<TimeProvider>(provider => provider.GetRequiredService<ManualClock>());
        collection.AddSingleton<FakeGooglePlay>();
        collection.AddSingleton<IGooglePlay>(provider => provider.GetRequiredService<FakeGooglePlay>());
        collection.AddSingleton<TestCurrentUser>();
        collection.AddSingleton<ICurrentUserService>(provider => provider.GetRequiredService<TestCurrentUser>());
        collection.AddSingleton<MobileAccess>();
        collection.AddScoped<Subscriptions>();
        collection.AddScoped<SubscriptionReconcileJob>();
        collection.AddScoped<BillingUserDataEraser>();
        var services = collection.BuildServiceProvider();

        using var scope = services.CreateScope();
        scope.ServiceProvider.GetRequiredService<BillingDbContext>().Database.EnsureCreated();
        return services;
    }
}

internal sealed class ManualClock : TimeProvider
{
    private DateTimeOffset now = new(2026, 10, 3, 12, 0, 0, TimeSpan.Zero);

    public override DateTimeOffset GetUtcNow() => now;

    public void Advance(TimeSpan by) => now += by;
}

internal sealed class TestCurrentUser : ICurrentUserService
{
    public string? UserId { get; set; }

    public string? SessionId => null;
}

/// <summary>Google as a test scripts it: what each token is, and what Kadans asked of it.</summary>
internal sealed class FakeGooglePlay : IGooglePlay
{
    public bool IsConfigured { get; set; } = true;
    public Dictionary<string, GoogleSubscription> Purchases { get; } = [];
    public List<string> Acknowledged { get; } = [];
    public List<string> Cancelled { get; } = [];
    public List<string> Read { get; } = [];

    public Task<GoogleSubscription?> GetAsync(string purchaseToken, CancellationToken cancellationToken)
    {
        Read.Add(purchaseToken);
        return Task.FromResult(Purchases.GetValueOrDefault(purchaseToken));
    }

    public Task AcknowledgeAsync(string productId, string purchaseToken, CancellationToken cancellationToken)
    {
        Acknowledged.Add(purchaseToken);
        return Task.CompletedTask;
    }

    public Task CancelAsync(string productId, string purchaseToken, CancellationToken cancellationToken)
    {
        Cancelled.Add(purchaseToken);
        return Task.CompletedTask;
    }
}
