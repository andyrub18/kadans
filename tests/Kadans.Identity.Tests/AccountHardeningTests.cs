using Kadans.Modules.Identity.Contracts;
using Kadans.Modules.Identity.Domain;
using Kadans.Modules.Identity.Features.Account;
using Kadans.Modules.Identity.Features.Auth;
using Kadans.Modules.Identity.Features.Users;
using Kadans.Modules.Identity.Security;
using Kadans.SharedKernel.Errors;
using Microsoft.AspNetCore.Identity;
using Microsoft.Data.Sqlite;
using Microsoft.Extensions.DependencyInjection;
using OneOf;

namespace Kadans.Identity.Tests;

/// <summary>
/// Sign-in and account changes on the real UserManager over in-memory SQLite: a lock after wrong passwords is not a
/// deactivation, sensitive changes take the password again, and an "@" in a username only names the owner's address.
/// </summary>
public sealed class AccountHardeningTests : IAsyncDisposable
{
    private const string Password = "Alice123!";

    private readonly SqliteConnection connection = new("DataSource=:memory:");
    private readonly ServiceProvider services;

    public AccountHardeningTests()
    {
        connection.Open();
        services = IdentityTestServices.Build(connection);
    }

    public async ValueTask DisposeAsync()
    {
        await services.DisposeAsync();
        await connection.DisposeAsync();
    }

    private async Task<T> InScopeAsync<T>(Func<IServiceProvider, Task<T>> action)
    {
        await using var scope = services.CreateAsyncScope();
        return await action(scope.ServiceProvider);
    }

    private Task<ApplicationUser> UserAsync(string name, string? password = Password, string? username = null) =>
        InScopeAsync(async p =>
        {
            var users = p.GetRequiredService<UserManager<ApplicationUser>>();
            var user = new ApplicationUser { UserName = username ?? name, Email = $"{name}@example.com", EmailConfirmed = true, LockoutEnabled = true };
            var created = password is null ? await users.CreateAsync(user) : await users.CreateAsync(user, password);
            if (!created.Succeeded)
                throw new InvalidOperationException(string.Join("; ", created.Errors.Select(e => e.Code)));
            return user;
        });

    private Task<OneOf<ApplicationError, LoginResponse>> LoginAsync(string login, string password) =>
        InScopeAsync(p => p.GetRequiredService<Authentication>().Login(new LoginRequest(login, password)));

    /// <summary>Acts as the signed-in <paramref name="user"/>.</summary>
    private void SignedInAs(ApplicationUser user) => services.GetRequiredService<TestCurrentUser>().UserId = user.Id;

    private static async Task ExpectError<T>(OneOf<ApplicationError, T> result, ErrorTypes type)
    {
        await Assert.That(result.IsT0).IsTrue();
        await Assert.That(result.AsT0.ErrorType).IsEqualTo(type);
    }

    [Test]
    public async Task Wrong_passwords_lock_the_account_for_a_while_without_calling_it_deactivated()
    {
        await UserAsync("alice");
        for (var attempt = 0; attempt < 5; attempt++)
            await ExpectError(await LoginAsync("alice", "wrong-password"), attempt < 4 ? ErrorTypes.InvalidCredentials : ErrorTypes.AccountLockedOut);

        // Even the right password waits for the lock to pass.
        await ExpectError(await LoginAsync("alice", Password), ErrorTypes.AccountLockedOut);
    }

    [Test]
    public async Task A_lock_after_wrong_passwords_leaves_existing_sessions_alone()
    {
        var alice = await UserAsync("alice");
        var session = (await LoginAsync("alice", Password)).AsT1;
        for (var attempt = 0; attempt < 5; attempt++)
            await LoginAsync("alice", "a stranger's guess");

        var refreshed = await InScopeAsync(p => p.GetRequiredService<Authentication>().RefreshToken(new RefreshTokenRequest(session.RefreshToken!)));

        await Assert.That(refreshed.IsT1).IsTrue();
        await Assert.That(await InScopeAsync(p => p.GetRequiredService<SessionRegistry>().IsOnAsync(SessionTests.SessionOf(refreshed.AsT1.AccessToken!)).AsTask()))
            .IsTrue();
    }

    [Test]
    public async Task A_deactivated_account_loses_its_sessions_at_the_next_refresh()
    {
        var alice = await UserAsync("alice");
        var session = (await LoginAsync("alice", Password)).AsT1;
        await InScopeAsync(async p =>
        {
            var users = p.GetRequiredService<UserManager<ApplicationUser>>();
            return await users.SetLockoutEndDateAsync((await users.FindByIdAsync(alice.Id))!, DateTimeOffset.MaxValue);
        });

        var refreshed = await InScopeAsync(p => p.GetRequiredService<Authentication>().RefreshToken(new RefreshTokenRequest(session.RefreshToken!)));

        await ExpectError(refreshed, ErrorTypes.UserInactive);
        await ExpectError(await LoginAsync("alice", Password), ErrorTypes.UserInactive);
    }

    [Test]
    public async Task Google_sign_in_is_not_held_back_by_a_lock_after_wrong_passwords()
    {
        await UserAsync("alice");
        await InScopeAsync(async p =>
        {
            var users = p.GetRequiredService<UserManager<ApplicationUser>>();
            return await users.AddLoginAsync((await users.FindByNameAsync("alice"))!, new UserLoginInfo("google", "alice-sub", "google"));
        });
        for (var attempt = 0; attempt < 5; attempt++)
            await LoginAsync("alice", "a stranger's guess");

        var google = await InScopeAsync(p => p.GetRequiredService<ExternalAuthentication>()
            .SignInAsync(new ExternalIdentity("google", "alice-sub", "alice@example.com", true, "Alice"), CancellationToken.None));

        await Assert.That(google.IsT1).IsTrue();
    }

    [Test]
    public async Task An_address_is_looked_up_as_an_address_first()
    {
        // From before the "@" rule: someone took Bob's address as a username.
        await UserAsync("mallory", username: "bob@example.com");
        await UserAsync("bob");

        var login = await LoginAsync("bob@example.com", Password);

        await Assert.That(login.IsT1).IsTrue();
        await Assert.That(SessionTests.UserOf(login.AsT1.AccessToken!)).IsEqualTo((await InScopeAsync(p =>
            p.GetRequiredService<UserManager<ApplicationUser>>().FindByEmailAsync("bob@example.com")))!.Id);
    }

    [Test]
    public async Task A_username_holds_an_at_sign_only_when_it_is_the_accounts_own_address()
    {
        var posing = await InScopeAsync(p => p.GetRequiredService<UserManagement>()
            .RegisterUser(new RegisterUserRequest("victim@example.com", Password, "eve@example.com")));
        var own = await InScopeAsync(p => p.GetRequiredService<UserManagement>()
            .RegisterUser(new RegisterUserRequest("Eve@Example.com", Password, "eve@example.com")));
        var plain = await InScopeAsync(p => p.GetRequiredService<UserManagement>()
            .RegisterUser(new RegisterUserRequest("frank", Password, "frank@example.com")));

        await ExpectError(posing, ErrorTypes.ValidationError);
        await Assert.That(((ValidationError)posing.AsT0).Errors.Single().Code).IsEqualTo("InvalidUserName");
        await Assert.That(own.IsT1).IsTrue();
        await Assert.That(plain.IsT1).IsTrue();
    }

    [Test]
    public async Task Changing_the_email_takes_the_current_password()
    {
        var alice = await UserAsync("alice");
        SignedInAs(alice);
        var sent = services.GetRequiredService<RecordingEmailSender>().Sent;

        var missing = await InScopeAsync(p => p.GetRequiredService<AccountSecurity>().RequestEmailChange(new ChangeEmailRequest("new@example.com"), CancellationToken.None));
        var wrong = await InScopeAsync(p => p.GetRequiredService<AccountSecurity>().RequestEmailChange(new ChangeEmailRequest("new@example.com", "nope"), CancellationToken.None));
        await Assert.That(sent).IsEmpty();
        var right = await InScopeAsync(p => p.GetRequiredService<AccountSecurity>().RequestEmailChange(new ChangeEmailRequest("new@example.com", Password), CancellationToken.None));

        await ExpectError(missing, ErrorTypes.ValidationError);
        await ExpectError(wrong, ErrorTypes.InvalidCredentials);
        await Assert.That(right.IsT1).IsTrue();
        await Assert.That(sent.Single().To).IsEqualTo("new@example.com");
    }

    [Test]
    public async Task An_account_that_only_uses_google_changes_its_email_without_a_password()
    {
        var alice = await UserAsync("alice", password: null);
        SignedInAs(alice);

        var result = await InScopeAsync(p => p.GetRequiredService<AccountSecurity>().RequestEmailChange(new ChangeEmailRequest("new@example.com"), CancellationToken.None));

        await Assert.That(result.IsT1).IsTrue();
    }

    [Test]
    public async Task Guessing_the_password_through_a_session_locks_the_account_too()
    {
        var alice = await UserAsync("alice");
        SignedInAs(alice);
        OneOf<ApplicationError, OneOf.Types.Success> last = default;
        for (var attempt = 0; attempt < 6; attempt++)
            last = await InScopeAsync(p => p.GetRequiredService<AccountSecurity>().ChangePassword(new ChangePasswordRequest($"guess-{attempt}", "Another123!")));

        await ExpectError(last, ErrorTypes.AccountLockedOut);
        await ExpectError(await LoginAsync("alice", Password), ErrorTypes.AccountLockedOut);
    }

    [Test]
    public async Task Wrong_codes_to_turn_2fa_off_count_toward_the_lock()
    {
        var alice = await UserAsync("alice");
        await InScopeAsync(async p =>
        {
            var users = p.GetRequiredService<UserManager<ApplicationUser>>();
            var user = (await users.FindByIdAsync(alice.Id))!;
            await users.ResetAuthenticatorKeyAsync(user);
            return await users.SetTwoFactorEnabledAsync(user, true);
        });
        SignedInAs(alice);

        OneOf<ApplicationError, OneOf.Types.Success> last = default;
        for (var attempt = 0; attempt < 6; attempt++)
            last = await InScopeAsync(p => p.GetRequiredService<AccountSecurity>().MfaDisable(new MfaCodeRequest("000000")));
        var codes = await InScopeAsync(p => p.GetRequiredService<AccountSecurity>().MfaRegenerateRecoveryCodes(new MfaCodeRequest("000000")));

        await ExpectError(last, ErrorTypes.AccountLockedOut);
        await ExpectError(codes, ErrorTypes.AccountLockedOut);
        await Assert.That(await InScopeAsync(async p =>
            (await p.GetRequiredService<UserManager<ApplicationUser>>().FindByIdAsync(alice.Id))!.TwoFactorEnabled)).IsTrue();
    }

    [Test]
    public async Task A_reset_link_lifts_a_lock_after_wrong_passwords()
    {
        await UserAsync("alice");
        for (var attempt = 0; attempt < 5; attempt++)
            await LoginAsync("alice", "a stranger's guess");
        var sent = services.GetRequiredService<RecordingEmailSender>().Sent;

        await InScopeAsync(p => p.GetRequiredService<AccountSecurity>().ForgotPassword(new ForgotPasswordRequest("alice@example.com"), CancellationToken.None));
        await Assert.That(sent.Count).IsEqualTo(1);
        var token = await InScopeAsync(async p =>
        {
            var users = p.GetRequiredService<UserManager<ApplicationUser>>();
            return IdentityEmails.Encode(await users.GeneratePasswordResetTokenAsync((await users.FindByNameAsync("alice"))!));
        });
        var reset = await InScopeAsync(p => p.GetRequiredService<AccountSecurity>().ResetPassword(new ResetPasswordRequest("alice@example.com", token, "Fresh1234!")));

        await Assert.That(reset.IsT1).IsTrue();
        await Assert.That((await LoginAsync("alice", "Fresh1234!")).IsT1).IsTrue();
    }

    [Test]
    public async Task A_username_that_was_the_old_address_follows_the_new_one()
    {
        // Created by Google sign-in: the address is the username.
        var alice = await UserAsync("alice", password: null, username: "alice@example.com");
        var token = await InScopeAsync(async p =>
        {
            var users = p.GetRequiredService<UserManager<ApplicationUser>>();
            return IdentityEmails.Encode(await users.GenerateChangeEmailTokenAsync((await users.FindByIdAsync(alice.Id))!, "alice@work.example"));
        });

        var changed = await InScopeAsync(p => p.GetRequiredService<AccountSecurity>().ConfirmEmailChangeByLink(alice.Id, "alice@work.example", token));

        await Assert.That(changed.IsT1).IsTrue();
        var user = await InScopeAsync(async p => (await p.GetRequiredService<UserManager<ApplicationUser>>().FindByIdAsync(alice.Id))!);
        await Assert.That(user.UserName).IsEqualTo("alice@work.example");
    }
}
