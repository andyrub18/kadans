using Kadans.Modules.Billing.Contracts;
using Kadans.Modules.Billing.Features;
using Kadans.SharedKernel.Errors;
using Kadans.SharedKernel.Users;
using Microsoft.Data.Sqlite;
using Microsoft.Extensions.DependencyInjection;

namespace Kadans.Billing.Tests;

/// <summary>
/// Free accounts, kept by an admin: found by username or confirmed address, effective at once (no restart), for that
/// account only, and gone with the account.
/// </summary>
public sealed class FreeAccountTests : IAsyncDisposable
{
    private const string Admin = "admin-id";
    private const string Marie = "marie-id";
    private const string Jean = "jean-id";

    private readonly SqliteConnection connection = new("DataSource=:memory:");
    private readonly ServiceProvider services;

    public FreeAccountTests()
    {
        connection.Open();
        services = BillingTestServices.Build(connection);
        var directory = services.GetRequiredService<FakeUserDirectory>();
        directory.Users.Add(new UserSummary(Marie, "Marie", "marie@example.com", "UTC", "fr", "marie", EmailConfirmed: true));
        directory.Users.Add(new UserSummary(Jean, null, "jean@example.com", "UTC", "ht", "jean", EmailConfirmed: false));
    }

    public async ValueTask DisposeAsync()
    {
        await services.DisposeAsync();
        await connection.DisposeAsync();
    }

    private async Task<T> As<T, TService>(string userId, Func<TService, Task<T>> action) where TService : notnull
    {
        services.GetRequiredService<TestCurrentUser>().UserId = userId;
        await using var scope = services.CreateAsyncScope();
        return await action(scope.ServiceProvider.GetRequiredService<TService>());
    }

    private Task<OneOf.OneOf<ApplicationError, FreeAccountResponse>> Add(string account) =>
        As<OneOf.OneOf<ApplicationError, FreeAccountResponse>, FreeAccounts>(Admin, s => s.Add(new AddFreeAccountRequest(account), CancellationToken.None));

    private async Task<SubscriptionStatusResponse> StatusOf(string userId) =>
        (await As<OneOf.OneOf<ApplicationError, SubscriptionStatusResponse>, Subscriptions>(userId, s => s.Status(CancellationToken.None))).AsT1;

    private Task<bool> PhonesAllowed(string userId) => services.GetRequiredService<MobileAccess>().AllowsPhonesAsync(userId);

    [Test]
    public async Task An_account_made_free_has_its_phones_at_once_and_only_that_account()
    {
        // Asked before (and remembered for a minute): no access yet.
        await Assert.That(await PhonesAllowed(Marie)).IsFalse();
        await Assert.That((await StatusOf(Marie)).HasAccess).IsFalse();

        var added = (await Add(" marie ")).AsT1;
        await Assert.That(added.UserId).IsEqualTo(Marie);
        await Assert.That(added.Email).IsEqualTo("marie@example.com");

        var status = await StatusOf(Marie);
        await Assert.That(status.Required).IsTrue();
        await Assert.That(status.HasAccess).IsTrue();
        await Assert.That(status.FreeAccess).IsTrue();
        await Assert.That(status.State).IsNull(); // nothing bought: nothing in a store to show
        await Assert.That(await PhonesAllowed(Marie)).IsTrue(); // no waiting out the cached answer

        await Assert.That((await StatusOf(Jean)).HasAccess).IsFalse();
        await Assert.That(await PhonesAllowed(Jean)).IsFalse();
    }

    [Test]
    public async Task Found_by_username_or_by_a_confirmed_address_only()
    {
        await Assert.That((await Add("MARIE@example.com")).AsT1.UserId).IsEqualTo(Marie);

        // Anyone can sign up with an address that is not theirs: an unconfirmed one names nobody.
        var unconfirmed = (await Add("jean@example.com")).AsT0;
        await Assert.That(unconfirmed.ErrorType).IsEqualTo(ErrorTypes.UserNotFound);
        await Assert.That((await Add("nobody")).AsT0.ErrorType).IsEqualTo(ErrorTypes.UserNotFound);
        await Assert.That((await Add("  ")).AsT0.ErrorType).IsEqualTo(ErrorTypes.UserNotFound);

        // The username works whatever the address.
        await Assert.That((await Add("jean")).AsT1.EmailConfirmed).IsFalse();
    }

    [Test]
    public async Task Adding_twice_keeps_one_entry_and_removing_ends_access_at_once()
    {
        var first = (await Add("marie")).AsT1;
        services.GetRequiredService<ManualClock>().Advance(TimeSpan.FromHours(1));
        var again = (await Add("marie")).AsT1;
        await Assert.That(again.AddedAt).IsEqualTo(first.AddedAt);

        var list = await As<List<FreeAccountResponse>, FreeAccounts>(Admin, s => s.List(CancellationToken.None));
        await Assert.That(list.Select(a => a.Username ?? "")).IsEquivalentTo(["marie"]);
        await Assert.That(await PhonesAllowed(Marie)).IsTrue();

        await As<OneOf.OneOf<ApplicationError, OneOf.Types.Success>, FreeAccounts>(Admin, s => s.Remove(Marie, CancellationToken.None));
        await Assert.That(await PhonesAllowed(Marie)).IsFalse();
        await Assert.That((await StatusOf(Marie)).FreeAccess).IsFalse();
        await Assert.That(await As<List<FreeAccountResponse>, FreeAccounts>(Admin, s => s.List(CancellationToken.None))).IsEmpty();

        // Removing what is not there is no error.
        await Assert.That((await As<OneOf.OneOf<ApplicationError, OneOf.Types.Success>, FreeAccounts>(Admin, s => s.Remove(Marie, CancellationToken.None))).IsT1).IsTrue();
    }

    [Test]
    public async Task An_erased_account_leaves_the_list()
    {
        await Add("marie");

        services.GetRequiredService<TestCurrentUser>().UserId = null;
        await using (var scope = services.CreateAsyncScope())
            await scope.ServiceProvider.GetRequiredService<BillingUserDataEraser>().EraseAsync(Marie);

        await Assert.That(await As<List<FreeAccountResponse>, FreeAccounts>(Admin, s => s.List(CancellationToken.None))).IsEmpty();
        await Assert.That(await PhonesAllowed(Marie)).IsFalse();
    }
}
