using System.IdentityModel.Tokens.Jwt;
using Kadans.Modules.Identity.Contracts;
using Kadans.Modules.Identity.Domain;
using Kadans.Modules.Identity.Features.Auth;
using Kadans.Modules.Identity.Features.Devices;
using Kadans.Modules.Identity.Persistence;
using Kadans.Modules.Identity.Security;
using Kadans.SharedKernel.Security;
using Kadans.SharedKernel.Users;
using Microsoft.AspNetCore.Identity;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace Kadans.Identity.Tests;

/// <summary>
/// Sign-in sessions end everywhere at once: the refresh tokens, the access tokens (from the next request), the
/// device the session registered, and its live connections. Real UserManager and token code over in-memory SQLite.
/// </summary>
public sealed class SessionTests : IAsyncDisposable
{
    private readonly SqliteConnection connection = new("DataSource=:memory:");
    private readonly ServiceProvider services;

    public SessionTests()
    {
        connection.Open();
        services = IdentityTestServices.Build(connection);
    }

    public async ValueTask DisposeAsync()
    {
        await services.DisposeAsync();
        await connection.DisposeAsync();
    }

    private sealed record Session(Guid Id, string AccessToken, string RefreshToken);

    private async Task<string> UserAsync(string name)
    {
        await using var scope = services.CreateAsyncScope();
        var user = new ApplicationUser { UserName = name, Email = $"{name}@example.com", EmailConfirmed = true };
        await scope.ServiceProvider.GetRequiredService<UserManager<ApplicationUser>>().CreateAsync(user);
        return user.Id;
    }

    /// <summary>A sign-in on a new device, which registers itself for push like the app does after sign-in.</summary>
    private async Task<Session> SignInAsync(string userId, Guid? installationId = null, string? pushToken = null)
    {
        await using var scope = services.CreateAsyncScope();
        var user = (await scope.ServiceProvider.GetRequiredService<UserManager<ApplicationUser>>().FindByIdAsync(userId))!;
        var login = await scope.ServiceProvider.GetRequiredService<Authentication>().IssueTokensOrChallengeAsync(user);
        var session = new Session(SessionOf(login.AccessToken!), login.AccessToken!, login.RefreshToken!);
        await RegisterDeviceAsync(userId, session, installationId ?? Guid.NewGuid(), pushToken ?? $"push-{session.Id}");
        return session;
    }

    private async Task RegisterDeviceAsync(string userId, Session session, Guid installationId, string pushToken)
    {
        var currentUser = services.GetRequiredService<TestCurrentUser>();
        currentUser.UserId = userId;
        currentUser.SessionId = session.Id.ToString();
        await using var scope = services.CreateAsyncScope();
        var registered = await scope.ServiceProvider.GetRequiredService<DeviceService>()
            .Register(installationId, new RegisterDeviceRequest(DevicePlatform.Android, "phone", pushToken, "1.0"));
        if (registered.IsT0)
            throw new InvalidOperationException(registered.AsT0.ErrorMessage);
    }

    internal static Guid SessionOf(string accessToken) =>
        Guid.Parse(new JwtSecurityTokenHandler().ReadJwtToken(accessToken).Claims.Single(c => c.Type == SessionClaim.Type).Value);

    internal static string UserOf(string accessToken) =>
        new JwtSecurityTokenHandler().ReadJwtToken(accessToken).Claims.Single(c => c.Type == System.Security.Claims.ClaimTypes.NameIdentifier).Value;

    private async Task<T> InScopeAsync<T>(Func<IServiceProvider, Task<T>> action)
    {
        await using var scope = services.CreateAsyncScope();
        return await action(scope.ServiceProvider);
    }

    private Task<bool> IsOnAsync(Session session) => services.GetRequiredService<SessionRegistry>().IsOnAsync(session.Id).AsTask();

    private Task<List<string>> PushTokensAsync(string userId) =>
        InScopeAsync(async provider =>
            (await provider.GetRequiredService<IDevicePushTargets>().ForUserAsync(userId)).Select(t => t.Token).ToList());

    [Test]
    public async Task A_refresh_keeps_the_session_and_every_access_token_names_it()
    {
        var userId = await UserAsync("alice");
        var session = await SignInAsync(userId);

        var refreshed = await InScopeAsync(p => p.GetRequiredService<Authentication>().RefreshToken(new RefreshTokenRequest(session.RefreshToken)));

        await Assert.That(refreshed.IsT1).IsTrue();
        await Assert.That(SessionOf(refreshed.AsT1.AccessToken!)).IsEqualTo(session.Id);
        await Assert.That(await IsOnAsync(session)).IsTrue();
    }

    [Test]
    public async Task Signing_out_ends_that_session_and_removes_its_device_only()
    {
        var userId = await UserAsync("alice");
        var phone = await SignInAsync(userId, pushToken: "phone-token");
        var laptop = await SignInAsync(userId, pushToken: "laptop-token");
        await Assert.That(await IsOnAsync(phone)).IsTrue(); // cached as on: the sign-out must drop that answer

        await InScopeAsync(async p =>
        {
            await p.GetRequiredService<Authentication>().RevokeRefreshToken(phone.RefreshToken);
            return 0;
        });

        await Assert.That(await IsOnAsync(phone)).IsFalse();
        await Assert.That(await IsOnAsync(laptop)).IsTrue();
        await Assert.That(await PushTokensAsync(userId)).IsEquivalentTo(["laptop-token"]);
        var refresh = await InScopeAsync(p => p.GetRequiredService<Authentication>().RefreshToken(new RefreshTokenRequest(phone.RefreshToken)));
        await Assert.That(refresh.IsT0).IsTrue();
        await Assert.That(services.GetRequiredService<RecordingListener>().Calls.Single().SessionIds).IsEquivalentTo([phone.Id.ToString()]);
    }

    [Test]
    public async Task Signing_out_everywhere_ends_every_session_and_every_device()
    {
        var userId = await UserAsync("alice");
        var phone = await SignInAsync(userId);
        var laptop = await SignInAsync(userId);
        var otherUser = await UserAsync("bob");
        var bobs = await SignInAsync(otherUser);

        var ended = await InScopeAsync(p => p.GetRequiredService<Sessions>().EndAllAsync(userId, "revoked by user"));

        await Assert.That(ended.SessionIds).IsEquivalentTo([phone.Id, laptop.Id]);
        await Assert.That(ended.Devices).IsEqualTo(2);
        await Assert.That(await IsOnAsync(phone)).IsFalse();
        await Assert.That(await IsOnAsync(laptop)).IsFalse();
        await Assert.That(await PushTokensAsync(userId)).IsEmpty();
        await Assert.That(await IsOnAsync(bobs)).IsTrue();
        await Assert.That((await PushTokensAsync(otherUser)).Count).IsEqualTo(1);
        await Assert.That(services.GetRequiredService<RecordingListener>().Calls.Single().SessionIds)
            .IsEquivalentTo([phone.Id.ToString(), laptop.Id.ToString()]);
    }

    [Test]
    public async Task A_replayed_refresh_token_ends_the_session_and_its_device()
    {
        var userId = await UserAsync("alice");
        var session = await SignInAsync(userId);
        await InScopeAsync(p => p.GetRequiredService<Authentication>().RefreshToken(new RefreshTokenRequest(session.RefreshToken)));

        // Someone else kept a copy of the token the app already rotated.
        var replay = await InScopeAsync(p => p.GetRequiredService<Authentication>().RefreshToken(new RefreshTokenRequest(session.RefreshToken)));

        await Assert.That(replay.IsT0).IsTrue();
        await Assert.That(await IsOnAsync(session)).IsFalse();
        await Assert.That(await PushTokensAsync(userId)).IsEmpty();
    }

    [Test]
    public async Task The_check_reads_a_session_at_most_once_a_minute_and_forgets_an_ended_one_at_once()
    {
        var userId = await UserAsync("alice");
        var session = await SignInAsync(userId);
        await Assert.That(await IsOnAsync(session)).IsTrue();

        // Revoked behind the registry's back (no announcement): the cached answer holds for the minute, no longer.
        await InScopeAsync(p => p.GetRequiredService<IdentityModuleDbContext>()
            .RefreshTokens.Where(t => t.FamilyId == session.Id)
            .ExecuteUpdateAsync(s => s.SetProperty(t => t.IsActive, false)));
        await Assert.That(await IsOnAsync(session)).IsTrue();
        services.GetRequiredService<ManualClock>().Advance(SessionRegistry.CacheFor + TimeSpan.FromSeconds(1));
        await Assert.That(await IsOnAsync(session)).IsFalse();

        // The normal way: announced, so refused on the very next check.
        var other = await SignInAsync(userId);
        await Assert.That(await IsOnAsync(other)).IsTrue();
        await InScopeAsync(p => p.GetRequiredService<Sessions>().EndAsync(userId, other.Id, "logout"));
        await Assert.That(await IsOnAsync(other)).IsFalse();
    }

    [Test]
    public async Task An_unknown_or_expired_session_is_off()
    {
        await Assert.That(await services.GetRequiredService<SessionRegistry>().IsOnAsync(Guid.NewGuid())).IsFalse();

        var userId = await UserAsync("alice");
        var session = await SignInAsync(userId);
        services.GetRequiredService<ManualClock>().Advance(TimeSpan.FromDays(31));
        await Assert.That(await IsOnAsync(session)).IsFalse();
    }

    [Test]
    public async Task A_phone_signed_into_another_account_stops_getting_the_first_accounts_reminders()
    {
        var installation = Guid.NewGuid();
        var alice = await UserAsync("alice");
        await SignInAsync(alice, installation, "shared-phone-token");
        var bob = await UserAsync("bob");

        // Alice signed out without network (her session never heard of it); Bob signs in on the same phone.
        await SignInAsync(bob, installation, "shared-phone-token");

        await Assert.That(await PushTokensAsync(alice)).IsEmpty();
        await Assert.That(await PushTokensAsync(bob)).IsEquivalentTo(["shared-phone-token"]);
        await Assert.That(await InScopeAsync(p => p.GetRequiredService<IdentityModuleDbContext>().Devices.CountAsync(d => d.InstallationId == installation)))
            .IsEqualTo(1);
    }

    [Test]
    public async Task A_reinstalled_app_takes_its_push_token_to_the_new_installation()
    {
        var alice = await UserAsync("alice");
        await SignInAsync(alice, Guid.NewGuid(), "phone-token");
        var bob = await UserAsync("bob");

        // Uninstalled and installed again (a new installation id), Google handed back the same token.
        await SignInAsync(bob, Guid.NewGuid(), "phone-token");

        await Assert.That(await PushTokensAsync(alice)).IsEmpty();
        await Assert.That(await PushTokensAsync(bob)).IsEquivalentTo(["phone-token"]);
    }
}
