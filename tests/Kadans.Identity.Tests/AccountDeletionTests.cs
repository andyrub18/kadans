using System.Text.RegularExpressions;
using Kadans.Modules.Identity.Contracts;
using Kadans.Modules.Identity.Domain;
using Kadans.Modules.Identity.Features.Account;
using Kadans.Modules.Identity.Features.Auth;
using Kadans.Modules.Identity.Persistence;
using Kadans.Modules.Identity.Security;
using Kadans.SharedKernel.Errors;
using Microsoft.AspNetCore.Identity;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;

namespace Kadans.Identity.Tests;

/// <summary>
/// "Delete my account": closed at once, erased with everything after 7 days unless kept, on the real UserManager and
/// token providers over in-memory SQLite. The other modules' erasers are a recording stand-in.
/// </summary>
public sealed class AccountDeletionTests : IAsyncDisposable
{
    private const string Password = "Alice123!";

    private readonly SqliteConnection connection = new("DataSource=:memory:");
    private readonly ServiceProvider services;

    public AccountDeletionTests()
    {
        connection.Open();
        services = IdentityTestServices.Build(connection);
    }

    public async ValueTask DisposeAsync()
    {
        await services.DisposeAsync();
        await connection.DisposeAsync();
    }

    private ManualClock Clock => services.GetRequiredService<ManualClock>();
    private List<Kadans.SharedKernel.Email.OutgoingEmail> Sent => services.GetRequiredService<RecordingEmailSender>().Sent;

    private async Task<T> InScopeAsync<T>(Func<IServiceProvider, Task<T>> action)
    {
        await using var scope = services.CreateAsyncScope();
        return await action(scope.ServiceProvider);
    }

    private Task<ApplicationUser> UserAsync(string name, bool withPassword = true) =>
        InScopeAsync(async p =>
        {
            var users = p.GetRequiredService<UserManager<ApplicationUser>>();
            var user = new ApplicationUser { UserName = name, Email = $"{name}@example.com", EmailConfirmed = true, LockoutEnabled = true };
            var created = withPassword ? await users.CreateAsync(user, Password) : await users.CreateAsync(user);
            if (!created.Succeeded)
                throw new InvalidOperationException(string.Join("; ", created.Errors.Select(e => e.Code)));
            return user;
        });

    private Task<OneOf.OneOf<ApplicationError, LoginResponse>> LoginAsync(string name) =>
        InScopeAsync(p => p.GetRequiredService<Authentication>().Login(new LoginRequest(name, Password)));

    private Task<OneOf.OneOf<ApplicationError, DeleteAccountResponse>> AskAsync(ApplicationUser user, string? password)
    {
        services.GetRequiredService<TestCurrentUser>().UserId = user.Id;
        return InScopeAsync(p => p.GetRequiredService<AccountDeletions>().Request(new DeleteAccountRequest(password), CancellationToken.None));
    }

    private Task RunErasureAsync() => InScopeAsync(async p =>
    {
        await p.GetRequiredService<AccountErasureJob>().RunAsync(CancellationToken.None);
        return 0;
    });

    private Task<AccountDeletion?> DeletionOf(string userId) =>
        InScopeAsync(p => p.GetRequiredService<IdentityModuleDbContext>().AccountDeletions.AsNoTracking().FirstOrDefaultAsync(d => d.UserId == userId));

    [Test]
    public async Task With_the_password_the_account_closes_now_and_is_set_for_erasure_a_week_out()
    {
        var alice = await UserAsync("alice");
        var session = (await LoginAsync("alice")).AsT1;

        var asked = await AskAsync(alice, Password);

        await Assert.That(asked.IsT1).IsTrue();
        await Assert.That(asked.AsT1.EraseAfter).IsEqualTo(Clock.GetUtcNow().AddDays(7));
        await Assert.That(await InScopeAsync(p => p.GetRequiredService<SessionRegistry>().IsOnAsync(SessionTests.SessionOf(session.AccessToken!)).AsTask())).IsFalse();
        await Assert.That(Sent.Single().Subject).IsEqualTo("Your Kadans account will be erased");
        await Assert.That(Sent.Single().TextBody).Contains("Sign in to Kadans before then");

        // Signing in proves who it is, but opens nothing: only the offer to keep the account.
        var signIn = (await LoginAsync("alice")).AsT1;
        await Assert.That(signIn.DeletionScheduled).IsTrue();
        await Assert.That(signIn.AccessToken).IsNull();
        await Assert.That(signIn.RestoreToken).IsNotNull();
        await Assert.That(signIn.EraseAfter).IsEqualTo(asked.AsT1.EraseAfter);
    }

    [Test]
    public async Task A_wrong_password_deletes_nothing_and_counts_toward_the_lock()
    {
        var alice = await UserAsync("alice");

        var missing = await AskAsync(alice, null);
        var wrong = await AskAsync(alice, "not-it");

        await Assert.That(missing.AsT0.ErrorType).IsEqualTo(ErrorTypes.ValidationError);
        await Assert.That(wrong.AsT0.ErrorType).IsEqualTo(ErrorTypes.InvalidCredentials);
        await Assert.That(await DeletionOf(alice.Id)).IsNull();
        await Assert.That(await InScopeAsync(async p => (await p.GetRequiredService<UserManager<ApplicationUser>>().FindByIdAsync(alice.Id))!.AccessFailedCount))
            .IsEqualTo(1);
    }

    [Test]
    public async Task An_account_that_only_uses_google_confirms_by_a_link_to_its_address()
    {
        var alice = await UserAsync("alice", withPassword: false);

        var asked = await AskAsync(alice, null);

        await Assert.That(asked.AsT1.ConfirmationSentTo).IsEqualTo("alice@example.com");
        await Assert.That(await DeletionOf(alice.Id)).IsNull(); // nothing yet: the link decides
        var link = Regex.Match(Sent.Single().TextBody, @"/account/delete/confirm\?userId=([^&\s]+)&token=(\S+)");
        await Assert.That(link.Success).IsTrue();

        var confirmed = await InScopeAsync(p => p.GetRequiredService<AccountDeletions>()
            .ConfirmByLink(Uri.UnescapeDataString(link.Groups[1].Value), link.Groups[2].Value, CancellationToken.None));

        await Assert.That(confirmed.IsT1).IsTrue();
        await Assert.That((await DeletionOf(alice.Id))!.EraseAfter).IsEqualTo(Clock.GetUtcNow().AddDays(7));
    }

    [Test]
    public async Task A_link_for_one_account_cannot_delete_another()
    {
        var alice = await UserAsync("alice", withPassword: false);
        var bob = await UserAsync("bob", withPassword: false);
        await AskAsync(alice, null);
        var link = Regex.Match(Sent.Single().TextBody, @"token=(\S+)");

        var forged = await InScopeAsync(p => p.GetRequiredService<AccountDeletions>().ConfirmByLink(bob.Id, link.Groups[1].Value, CancellationToken.None));

        await Assert.That(forged.IsT0).IsTrue();
        await Assert.That(await DeletionOf(bob.Id)).IsNull();
    }

    [Test]
    public async Task Keeping_the_account_from_the_sign_in_offer_reopens_it()
    {
        var alice = await UserAsync("alice");
        await AskAsync(alice, Password);
        var offer = (await LoginAsync("alice")).AsT1;

        var kept = await InScopeAsync(p => p.GetRequiredService<AccountDeletions>().Restore(new RestoreAccountRequest(offer.RestoreToken!)));

        await Assert.That(kept.IsT1).IsTrue();
        await Assert.That(kept.AsT1.AccessToken).IsNotNull();
        await Assert.That(await DeletionOf(alice.Id)).IsNull();
        await Assert.That((await LoginAsync("alice")).AsT1.DeletionScheduled).IsFalse();
        // A sign-in token is not a restore token, and the reverse.
        var bogus = await InScopeAsync(p => p.GetRequiredService<AccountDeletions>().Restore(new RestoreAccountRequest(kept.AsT1.AccessToken!)));
        await Assert.That(bogus.IsT0).IsTrue();
    }

    [Test]
    public async Task After_seven_days_the_account_and_everything_in_it_are_erased()
    {
        var alice = await UserAsync("alice");
        var bob = await UserAsync("bob");
        await AskAsync(alice, Password);

        Clock.Advance(TimeSpan.FromDays(6));
        await RunErasureAsync();
        await Assert.That(services.GetRequiredService<RecordingEraser>().Erased).IsEmpty();

        Clock.Advance(TimeSpan.FromDays(1).Add(TimeSpan.FromMinutes(1)));
        await RunErasureAsync();

        await Assert.That(services.GetRequiredService<RecordingEraser>().Erased).IsEquivalentTo([alice.Id]);
        await Assert.That(await InScopeAsync(p => p.GetRequiredService<UserManager<ApplicationUser>>().FindByIdAsync(alice.Id))).IsNull();
        await Assert.That(await InScopeAsync(p => p.GetRequiredService<UserManager<ApplicationUser>>().FindByIdAsync(bob.Id))).IsNotNull();
        // What stays: the id and the dates, so a restored backup can be cleaned again.
        await Assert.That((await DeletionOf(alice.Id))!.ErasedAt).IsNotNull();
        await Assert.That(Sent.Last().Subject).IsEqualTo("Your Kadans account has been erased");
        await Assert.That(Sent.Last().To).IsEqualTo("alice@example.com");

        // And the record goes after 30 days.
        await InScopeAsync(async p =>
        {
            await new IdentityRetentionJob(p.GetRequiredService<IdentityModuleDbContext>(), Options.Create(new IdentityRetentionOptions()), NullLogger<IdentityRetentionJob>.Instance)
                .RunAsync(Clock.GetUtcNow().AddDays(31), CancellationToken.None);
            return 0;
        });
        await Assert.That(await DeletionOf(alice.Id)).IsNull();
    }

    [Test]
    public async Task The_web_form_answers_the_same_for_every_address()
    {
        await UserAsync("alice", withPassword: false);

        await InScopeAsync(async p =>
        {
            await p.GetRequiredService<AccountDeletions>().RequestByEmail("nobody@example.com", CancellationToken.None);
            return 0;
        });
        await Assert.That(Sent).IsEmpty();

        await InScopeAsync(async p =>
        {
            await p.GetRequiredService<AccountDeletions>().RequestByEmail("alice@example.com", CancellationToken.None);
            return 0;
        });
        await Assert.That(Sent.Single().Subject).IsEqualTo("Confirm the deletion of your Kadans account");
    }

    [Test]
    public async Task The_erasure_date_reads_naturally_in_every_language()
    {
        var at = new DateTimeOffset(2026, 10, 10, 3, 0, 0, TimeSpan.Zero);
        var haiti = TimeZoneInfo.FindSystemTimeZoneById("America/Port-au-Prince");

        await Assert.That(DeletionTexts.For("en").Date(at, haiti)).IsEqualTo("October 9, 2026"); // 23:00 the day before in Haiti
        await Assert.That(DeletionTexts.For("fr").Date(at, haiti)).IsEqualTo("9 octobre 2026");
        await Assert.That(DeletionTexts.For("ht").Date(at, haiti)).IsEqualTo("9 oktòb 2026");
    }
}
