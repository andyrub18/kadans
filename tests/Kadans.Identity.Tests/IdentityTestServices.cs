using Kadans.Modules.Identity.Domain;
using Kadans.Modules.Identity.Features.Auth;
using Kadans.Modules.Identity.Features.Devices;
using Kadans.Modules.Identity.Persistence;
using Kadans.Modules.Identity.Security;
using Kadans.SharedKernel.Security;
using Kadans.SharedKernel.Users;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.Identity;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace Kadans.Identity.Tests;

/// <summary>
/// The Identity module's account services on the real UserManager and token providers, over an in-memory SQLite
/// database (the caller keeps the connection open). The current user, the clock and the session listeners are
/// test doubles the caller can drive.
/// </summary>
internal static class IdentityTestServices
{
    public static ServiceProvider Build(SqliteConnection connection)
    {
        var collection = new ServiceCollection();
        collection.AddLogging();
        collection.AddDbContext<IdentityModuleDbContext>(options => options.UseSqlite(connection));
        collection
            .AddIdentity<ApplicationUser, IdentityRole>(options => options.User.RequireUniqueEmail = true)
            .AddEntityFrameworkStores<IdentityModuleDbContext>()
            .AddDefaultTokenProviders();
        collection.AddDataProtection().UseEphemeralDataProtectionProvider();
        collection.Configure<JwtParameter>(jwt =>
        {
            jwt.Key = new string('k', 64);
            jwt.Issuer = "Kadans";
            jwt.Audience = "Kadans.Clients";
            jwt.ExpirationInMinutes = 60;
            jwt.RefreshTokenExpirationInDays = 30;
        });
        collection.AddSingleton<ManualClock>();
        collection.AddSingleton<TimeProvider>(provider => provider.GetRequiredService<ManualClock>());
        collection.AddSingleton<RecordingListener>();
        collection.AddSingleton<ISessionEndListener>(provider => provider.GetRequiredService<RecordingListener>());
        collection.AddSingleton<TestCurrentUser>();
        collection.AddSingleton<ICurrentUserService>(provider => provider.GetRequiredService<TestCurrentUser>());
        collection.AddSingleton<SessionRegistry>();
        collection.AddScoped<Sessions>();
        collection.AddScoped<JwtProvider>();
        collection.AddScoped<Authentication>();
        collection.AddScoped<DeviceService>();
        collection.AddScoped<IDevicePushTargets, DevicePushTargets>();
        collection.AddSingleton<ExternalIdTokenValidator>();
        collection.AddHttpClient<GoogleCodeExchange>();
        collection.AddScoped<ExternalAuthentication>();
        var services = collection.BuildServiceProvider();

        using var scope = services.CreateScope();
        scope.ServiceProvider.GetRequiredService<IdentityModuleDbContext>().Database.EnsureCreated();
        return services;
    }
}

/// <summary>A clock that moves only when told to.</summary>
internal sealed class ManualClock : TimeProvider
{
    private DateTimeOffset now = DateTimeOffset.UtcNow;

    public override DateTimeOffset GetUtcNow() => now;

    public void Advance(TimeSpan by) => now += by;
}

/// <summary>Every <see cref="ISessionEndListener.SessionsEnded"/> call, in order.</summary>
internal sealed class RecordingListener : ISessionEndListener
{
    public List<(string UserId, string[] SessionIds)> Calls { get; } = [];

    public void SessionsEnded(string userId, IReadOnlyCollection<string> sessionIds) => Calls.Add((userId, sessionIds.ToArray()));
}

/// <summary>The signed-in user and session a request would carry.</summary>
internal sealed class TestCurrentUser : ICurrentUserService
{
    public string? UserId { get; set; }

    public string? SessionId { get; set; }
}
