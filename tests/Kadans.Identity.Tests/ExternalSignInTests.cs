using Kadans.Modules.Identity.Contracts;
using Kadans.Modules.Identity.Domain;
using Kadans.Modules.Identity.Features.Auth;
using Kadans.Modules.Identity.Persistence;
using Kadans.Modules.Identity.Security;
using Kadans.SharedKernel.Errors;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.Identity;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using OneOf;

namespace Kadans.Identity.Tests;

/// <summary>
/// The account side of an external sign-in, on the real UserManager and token providers over an in-memory
/// SQLite database. The provider's ID token is taken as already verified: what is under test is which account
/// the sign-in lands in, and what an unproven registrant keeps.
/// </summary>
public sealed class ExternalSignInTests : IAsyncDisposable
{
    private const string OwnerEmail = "owner@gmail.com";
    private const string SquatterPassword = "Squat123!";

    private readonly SqliteConnection connection = new("DataSource=:memory:");
    private readonly ServiceProvider services;

    public ExternalSignInTests()
    {
        connection.Open();
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
        });
        collection.AddScoped<JwtProvider>();
        collection.AddScoped<Authentication>();
        collection.AddSingleton<ExternalIdTokenValidator>();
        collection.AddHttpClient<GoogleCodeExchange>();
        collection.AddScoped<ExternalAuthentication>();
        services = collection.BuildServiceProvider();

        using var scope = services.CreateScope();
        scope.ServiceProvider.GetRequiredService<IdentityModuleDbContext>().Database.EnsureCreated();
    }

    public async ValueTask DisposeAsync()
    {
        await services.DisposeAsync();
        await connection.DisposeAsync();
    }

    [Test]
    public async Task An_unconfirmed_account_with_the_verified_address_is_taken_over_by_its_owner()
    {
        // Someone registered the owner's address with their own password, then set up 2FA, another sign-in,
        // a phone for push and a session.
        var squatterId = await SquatterAsync(OwnerEmail);

        var result = await SignInAsync(Google(OwnerEmail));

        await Assert.That(result.IsT1).IsTrue();
        await Assert.That(result.AsT1.MfaRequired).IsFalse(); // the squatter's 2FA no longer stands in the way
        await using var scope = services.CreateAsyncScope();
        var users = scope.ServiceProvider.GetRequiredService<UserManager<ApplicationUser>>();
        var db = scope.ServiceProvider.GetRequiredService<IdentityModuleDbContext>();
        var owner = (await users.FindByIdAsync(squatterId))!;
        await Assert.That(owner.EmailConfirmed).IsTrue();
        await Assert.That(await users.HasPasswordAsync(owner)).IsFalse();
        await Assert.That(await users.CheckPasswordAsync(owner, SquatterPassword)).IsFalse();
        await Assert.That(owner.TwoFactorEnabled).IsFalse();
        await Assert.That(await users.CountRecoveryCodesAsync(owner)).IsEqualTo(0);
        await Assert.That((await users.GetLoginsAsync(owner)).Select(l => $"{l.LoginProvider}/{l.ProviderKey}")).IsEquivalentTo(["google/owner-sub"]);
        await Assert.That(await db.Devices.CountAsync(d => d.UserId == squatterId)).IsEqualTo(0);
        await Assert.That(await db.RefreshTokens.AnyAsync(t => t.UserId == squatterId && t.TokenHash == "squatter-session" && t.IsActive)).IsFalse();
    }

    [Test]
    public async Task A_squatter_cannot_lock_the_owner_out_by_deactivating_the_account()
    {
        var squatterId = await SquatterAsync(OwnerEmail);
        await using (var scope = services.CreateAsyncScope())
        {
            var users = scope.ServiceProvider.GetRequiredService<UserManager<ApplicationUser>>();
            await users.SetLockoutEndDateAsync((await users.FindByIdAsync(squatterId))!, DateTimeOffset.MaxValue);
        }

        var result = await SignInAsync(Google(OwnerEmail));

        await Assert.That(result.IsT1).IsTrue();
    }

    [Test]
    public async Task A_confirmed_account_is_linked_and_keeps_its_password_and_2fa()
    {
        string ownerId;
        await using (var scope = services.CreateAsyncScope())
        {
            var users = scope.ServiceProvider.GetRequiredService<UserManager<ApplicationUser>>();
            var owner = new ApplicationUser { UserName = "owner", Email = OwnerEmail, EmailConfirmed = true, LockoutEnabled = true };
            await users.CreateAsync(owner, "Owner123!");
            await users.ResetAuthenticatorKeyAsync(owner);
            await users.SetTwoFactorEnabledAsync(owner, true);
            ownerId = owner.Id;
        }

        var result = await SignInAsync(Google(OwnerEmail));

        await Assert.That(result.AsT1.MfaRequired).IsTrue(); // 2FA guards Google sign-in too
        await using var check = services.CreateAsyncScope();
        var manager = check.ServiceProvider.GetRequiredService<UserManager<ApplicationUser>>();
        var linked = (await manager.FindByIdAsync(ownerId))!;
        await Assert.That(await manager.CheckPasswordAsync(linked, "Owner123!")).IsTrue();
        await Assert.That(linked.TwoFactorEnabled).IsTrue();
        await Assert.That((await manager.GetLoginsAsync(linked)).Count).IsEqualTo(1);
    }

    [Test]
    [Arguments("someone@example.com")] // matches an existing account
    [Arguments("nobody@example.com")] // would create one on an unproven address
    public async Task A_sign_in_without_a_verified_address_links_and_creates_nothing(string email)
    {
        string existingId;
        await using (var scope = services.CreateAsyncScope())
        {
            var users = scope.ServiceProvider.GetRequiredService<UserManager<ApplicationUser>>();
            var existing = new ApplicationUser { UserName = "someone", Email = "someone@example.com", EmailConfirmed = true, LockoutEnabled = true };
            await users.CreateAsync(existing, "Someone123!");
            existingId = existing.Id;
        }

        var result = await SignInAsync(Google(email, verified: false, subject: "unverified-sub"));

        await Assert.That(result.AsT0.ErrorType).IsEqualTo(ErrorTypes.ExternalLoginFailed);
        await using var check = services.CreateAsyncScope();
        var manager = check.ServiceProvider.GetRequiredService<UserManager<ApplicationUser>>();
        await Assert.That(await manager.Users.CountAsync()).IsEqualTo(1);
        await Assert.That((await manager.GetLoginsAsync((await manager.FindByIdAsync(existingId))!)).Count).IsEqualTo(0);
    }

    [Test]
    public async Task A_returning_user_signs_in_to_the_same_account()
    {
        var first = await SignInAsync(Google("new@gmail.com", subject: "returning-sub"));
        var second = await SignInAsync(Google("new@gmail.com", subject: "returning-sub"));

        await Assert.That(first.IsT1 && second.IsT1).IsTrue();
        await using var check = services.CreateAsyncScope();
        var manager = check.ServiceProvider.GetRequiredService<UserManager<ApplicationUser>>();
        var user = (await manager.FindByLoginAsync("google", "returning-sub"))!;
        await Assert.That(user.Email).IsEqualTo("new@gmail.com");
        await Assert.That(user.EmailConfirmed).IsTrue();
        await Assert.That(await manager.Users.CountAsync()).IsEqualTo(1);
    }

    [Test]
    public async Task A_new_account_takes_the_devices_time_zone_and_language()
    {
        await using var scope = services.CreateAsyncScope();
        var external = scope.ServiceProvider.GetRequiredService<ExternalAuthentication>();
        await external.SignInAsync(Google("haiti@gmail.com", subject: "haiti-sub"), CancellationToken.None, new NewAccountProfile("America/Port-au-Prince", "HT"));
        await external.SignInAsync(Google("odd@gmail.com", subject: "odd-sub"), CancellationToken.None, new NewAccountProfile("Mars/Olympus_Mons", "de"));

        var users = scope.ServiceProvider.GetRequiredService<UserManager<ApplicationUser>>();
        var haiti = (await users.FindByLoginAsync("google", "haiti-sub"))!;
        var odd = (await users.FindByLoginAsync("google", "odd-sub"))!;
        await Assert.That(haiti.TimeZoneId).IsEqualTo("America/Port-au-Prince");
        await Assert.That(haiti.PreferredLanguage).IsEqualTo("ht");
        await Assert.That(odd.TimeZoneId).IsEqualTo("UTC"); // unknown zone and language fall back, as at registration
        await Assert.That(odd.PreferredLanguage).IsEqualTo("en");
    }

    [Test]
    public async Task Linking_an_existing_account_leaves_its_profile_alone()
    {
        string ownerId;
        await using (var scope = services.CreateAsyncScope())
        {
            var users = scope.ServiceProvider.GetRequiredService<UserManager<ApplicationUser>>();
            var owner = new ApplicationUser { UserName = "owner", Email = OwnerEmail, EmailConfirmed = true, LockoutEnabled = true, TimeZoneId = "Europe/Paris", PreferredLanguage = "fr" };
            await users.CreateAsync(owner, "Owner123!");
            ownerId = owner.Id;
        }

        await using var check = services.CreateAsyncScope();
        await check.ServiceProvider.GetRequiredService<ExternalAuthentication>()
            .SignInAsync(Google(OwnerEmail), CancellationToken.None, new NewAccountProfile("America/Port-au-Prince", "ht"));

        var linked = (await check.ServiceProvider.GetRequiredService<UserManager<ApplicationUser>>().FindByIdAsync(ownerId))!;
        await Assert.That(linked.TimeZoneId).IsEqualTo("Europe/Paris");
        await Assert.That(linked.PreferredLanguage).IsEqualTo("fr");
    }

    private static ExternalIdentity Google(string email, bool verified = true, string subject = "owner-sub") =>
        new(ExternalIdTokenValidator.Google, subject, email, verified, "Owner");

    private async Task<OneOf<ApplicationError, LoginResponse>> SignInAsync(ExternalIdentity external)
    {
        await using var scope = services.CreateAsyncScope();
        return await scope.ServiceProvider.GetRequiredService<ExternalAuthentication>().SignInAsync(external, CancellationToken.None);
    }

    private async Task<string> SquatterAsync(string email)
    {
        await using var scope = services.CreateAsyncScope();
        var users = scope.ServiceProvider.GetRequiredService<UserManager<ApplicationUser>>();
        var squatter = new ApplicationUser { UserName = "squatter", Email = email, LockoutEnabled = true };
        await users.CreateAsync(squatter, SquatterPassword);
        await users.ResetAuthenticatorKeyAsync(squatter);
        await users.SetTwoFactorEnabledAsync(squatter, true);
        await users.GenerateNewTwoFactorRecoveryCodesAsync(squatter, 8);
        await users.AddLoginAsync(squatter, new UserLoginInfo("apple", "squatter-apple", "apple"));

        var db = scope.ServiceProvider.GetRequiredService<IdentityModuleDbContext>();
        db.Devices.Add(new Device { InstallationId = Guid.NewGuid(), UserId = squatter.Id, Name = "squatter phone", PushToken = "squatter-fcm" });
        db.RefreshTokens.Add(new RefreshToken
        {
            TokenHash = "squatter-session",
            FamilyId = Guid.NewGuid(),
            UserId = squatter.Id,
            IsActive = true,
            CreatedAtUtc = DateTimeOffset.UtcNow,
            ExpireAtUtc = DateTimeOffset.UtcNow.AddDays(30),
        });
        await db.SaveChangesAsync();
        return squatter.Id;
    }
}
