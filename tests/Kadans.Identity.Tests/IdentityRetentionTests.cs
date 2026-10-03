using Kadans.Modules.Identity.Domain;
using Kadans.Modules.Identity.Features.Auth;
using Kadans.Modules.Identity.Persistence;
using Microsoft.AspNetCore.Identity;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;

namespace Kadans.Identity.Tests;

/// <summary>The nightly cleanup of sign-in tokens and devices, on the real schema over in-memory SQLite.</summary>
public sealed class IdentityRetentionTests : IAsyncDisposable
{
    private static readonly DateTimeOffset Now = new(2027, 6, 1, 7, 30, 0, TimeSpan.Zero);

    private readonly SqliteConnection connection = new("DataSource=:memory:");
    private readonly ServiceProvider services;

    public IdentityRetentionTests()
    {
        connection.Open();
        services = IdentityTestServices.Build(connection);
    }

    public async ValueTask DisposeAsync()
    {
        await services.DisposeAsync();
        await connection.DisposeAsync();
    }

    [Test]
    public async Task Tokens_go_a_week_after_they_expire_and_unreachable_idle_devices_after_half_a_year()
    {
        await using (var scope = services.CreateAsyncScope())
        {
            var user = new ApplicationUser { UserName = "alice", Email = "alice@example.com" };
            await scope.ServiceProvider.GetRequiredService<UserManager<ApplicationUser>>().CreateAsync(user);
            var db = scope.ServiceProvider.GetRequiredService<IdentityModuleDbContext>();
            RefreshToken Token(string name, DateTimeOffset expires) => new()
            {
                TokenHash = name, FamilyId = Guid.NewGuid(), CreatedAtUtc = expires.AddDays(-30), ExpireAtUtc = expires, UserId = user.Id,
            };
            Device Device(string name, DateTimeOffset lastSeen, string? pushToken) => new()
            {
                InstallationId = Guid.NewGuid(), UserId = user.Id, Name = name, PushToken = pushToken, LastSeenAt = lastSeen,
            };
            db.RefreshTokens.AddRange(
                Token("expired-long-ago", Now.AddDays(-8)),
                Token("expired-yesterday", Now.AddDays(-1)),
                Token("live", Now.AddDays(20))
            );
            db.Devices.AddRange(
                Device("old desktop", Now.AddDays(-200), pushToken: null),
                Device("phone that only shows reminders", Now.AddDays(-400), pushToken: "fcm-live"),
                Device("laptop used last month", Now.AddDays(-30), pushToken: null)
            );
            await db.SaveChangesAsync();
        }

        await using (var scope = services.CreateAsyncScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<IdentityModuleDbContext>();
            await new IdentityRetentionJob(db, Options.Create(new IdentityRetentionOptions()), NullLogger<IdentityRetentionJob>.Instance)
                .RunAsync(Now, CancellationToken.None);
        }

        await using (var scope = services.CreateAsyncScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<IdentityModuleDbContext>();
            await Assert.That(await db.RefreshTokens.Select(t => t.TokenHash).OrderBy(h => h).ToListAsync())
                .IsEquivalentTo(["expired-yesterday", "live"]);
            await Assert.That(await db.Devices.Select(d => d.Name).OrderBy(n => n).ToListAsync())
                .IsEquivalentTo(["laptop used last month", "phone that only shows reminders"]);
        }
    }
}
