using System.Text;
using Kadans.Modules.Billing.Contracts;
using Kadans.Modules.Billing.Domain;
using Kadans.Modules.Billing.Features;
using Kadans.Modules.Billing.Google;
using Kadans.Modules.Billing.Persistence;
using Kadans.SharedKernel.Errors;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using OneOf;

namespace Kadans.Billing.Tests;

/// <summary>
/// Subscriptions as the stores report them: a purchase is checked with Google and only ever counts for the account it
/// was made for; notifications are read again from Google; phones are allowed while paid up.
/// </summary>
public sealed class SubscriptionTests : IAsyncDisposable
{
    private const string Alice = "alice-id";
    private const string Bob = "bob-id";

    private readonly SqliteConnection connection = new("DataSource=:memory:");
    private readonly ServiceProvider services;

    public SubscriptionTests()
    {
        connection.Open();
        services = BillingTestServices.Build(connection);
    }

    public async ValueTask DisposeAsync()
    {
        await services.DisposeAsync();
        await connection.DisposeAsync();
    }

    private FakeGooglePlay Google => services.GetRequiredService<FakeGooglePlay>();
    private ManualClock Clock => services.GetRequiredService<ManualClock>();
    private DateTimeOffset Now => Clock.GetUtcNow();

    /// <summary>Google knows this token: Kadans' subscription, made in the app by <paramref name="forUser"/>.</summary>
    private void Bought(string token, string forUser, string state = "SUBSCRIPTION_STATE_ACTIVE", string? offer = "trial-14d", int days = 14,
        bool acknowledged = false, string? replaces = null, string product = "kadans_mobile") =>
        Google.Purchases[token] = new GoogleSubscription(state, product, Now.AddDays(days), true, offer, AccountHash.Of(forUser), replaces, acknowledged);

    private async Task<T> As<T>(string userId, Func<Subscriptions, Task<T>> action)
    {
        services.GetRequiredService<TestCurrentUser>().UserId = userId;
        await using var scope = services.CreateAsyncScope();
        return await action(scope.ServiceProvider.GetRequiredService<Subscriptions>());
    }

    private Task<OneOf<ApplicationError, SubscriptionStatusResponse>> Link(string userId, string token) =>
        As(userId, s => s.LinkGoogle(new GooglePurchaseRequest(token), CancellationToken.None));

    private Task<bool> PhonesAllowed(string userId) => services.GetRequiredService<MobileAccess>().AllowsPhonesAsync(userId);

    private async Task<StoreSubscription?> Row(string token)
    {
        await using var scope = services.CreateAsyncScope();
        return await scope.ServiceProvider.GetRequiredService<BillingDbContext>().Subscriptions.IgnoreQueryFilters().AsNoTracking()
            .FirstOrDefaultAsync(s => s.StoreKey == token);
    }

    [Test]
    public async Task Googles_words_for_a_subscription_in_kadans_words()
    {
        GoogleSubscription Said(string state, string? offer = null) => new(state, "kadans_mobile", null, true, offer, null, null, true);

        await Assert.That(GoogleStates.From(Said("SUBSCRIPTION_STATE_ACTIVE", "trial-14d"), "trial-14d")).IsEqualTo(SubscriptionState.Trial);
        await Assert.That(GoogleStates.From(Said("SUBSCRIPTION_STATE_ACTIVE"), "trial-14d")).IsEqualTo(SubscriptionState.Active);
        await Assert.That(GoogleStates.From(Said("SUBSCRIPTION_STATE_IN_GRACE_PERIOD"), "trial-14d")).IsEqualTo(SubscriptionState.GracePeriod);
        await Assert.That(GoogleStates.From(Said("SUBSCRIPTION_STATE_ON_HOLD"), "trial-14d")).IsEqualTo(SubscriptionState.OnHold);
        await Assert.That(GoogleStates.From(Said("SUBSCRIPTION_STATE_PAUSED"), "trial-14d")).IsEqualTo(SubscriptionState.Paused);
        await Assert.That(GoogleStates.From(Said("SUBSCRIPTION_STATE_CANCELED"), "trial-14d")).IsEqualTo(SubscriptionState.Canceled);
        await Assert.That(GoogleStates.From(Said("SUBSCRIPTION_STATE_EXPIRED"), "trial-14d")).IsEqualTo(SubscriptionState.Expired);
        await Assert.That(GoogleStates.From(Said("SUBSCRIPTION_STATE_PENDING"), "trial-14d")).IsEqualTo(SubscriptionState.Pending);
    }

    [Test]
    public async Task A_purchase_checked_with_google_opens_the_phones_and_is_acknowledged()
    {
        await Assert.That(await PhonesAllowed(Alice)).IsFalse();
        Bought("token-1", Alice);

        var status = await Link(Alice, "token-1");

        await Assert.That(status.IsT1).IsTrue();
        await Assert.That(status.AsT1.HasAccess).IsTrue();
        await Assert.That(status.AsT1.State).IsEqualTo(SubscriptionState.Trial);
        await Assert.That(status.AsT1.ExpiresAt).IsEqualTo(Now.AddDays(14));
        await Assert.That(Google.Acknowledged).IsEquivalentTo(["token-1"]);
        await Assert.That(await PhonesAllowed(Alice)).IsTrue();
        // Restoring it again is harmless and acknowledges nothing twice once Google says so.
        Bought("token-1", Alice, acknowledged: true);
        await Assert.That((await Link(Alice, "token-1")).IsT1).IsTrue();
        await Assert.That(Google.Acknowledged.Count).IsEqualTo(1);
    }

    [Test]
    public async Task A_purchase_only_ever_counts_for_the_account_it_was_made_for()
    {
        Bought("bobs-token", Bob);

        var stolen = await Link(Alice, "bobs-token");
        await Assert.That(stolen.AsT0.ErrorType).IsEqualTo(ErrorTypes.PurchaseOtherAccount);
        await Assert.That(await Row("bobs-token")).IsNull();

        // Linked by Bob, then presented by Alice with a purchase naming her (it cannot, but the database says no too).
        await Link(Bob, "bobs-token");
        Bought("bobs-token", Alice);
        await Assert.That((await Link(Alice, "bobs-token")).AsT0.ErrorType).IsEqualTo(ErrorTypes.PurchaseOtherAccount);
        await Assert.That((await Row("bobs-token"))!.UserId).IsEqualTo(Bob);
    }

    [Test]
    public async Task What_google_does_not_vouch_for_is_refused()
    {
        await Assert.That((await Link(Alice, "made-up")).AsT0.ErrorType).IsEqualTo(ErrorTypes.PurchaseNotVerified);
        Bought("other-product", Alice, product: "someone_elses_app");
        await Assert.That((await Link(Alice, "other-product")).AsT0.ErrorType).IsEqualTo(ErrorTypes.PurchaseNotVerified);

        Google.IsConfigured = false;
        Bought("token-1", Alice);
        await Assert.That((await Link(Alice, "token-1")).AsT0.ErrorType).IsEqualTo(ErrorTypes.BillingUnavailable);
    }

    [Test]
    public async Task A_resubscription_ends_the_purchase_it_replaces()
    {
        Bought("first", Alice);
        await Link(Alice, "first");

        Bought("second", Alice, offer: null, days: 30, replaces: "first");
        await Link(Alice, "second");

        await Assert.That((await Row("first"))!.State).IsEqualTo(SubscriptionState.Expired);
        await Assert.That((await Row("second"))!.State).IsEqualTo(SubscriptionState.Active);
    }

    [Test]
    public async Task A_notification_is_read_again_from_google_and_a_refund_ends_access_now()
    {
        Bought("token-1", Alice);
        await Link(Alice, "token-1");

        // Renewal failed: Google retries, Alice keeps access meanwhile; then the account is on hold.
        Bought("token-1", Alice, state: "SUBSCRIPTION_STATE_IN_GRACE_PERIOD");
        await As(Alice, async s => { await s.SyncGoogle("token-1", revoked: false, CancellationToken.None); return 0; });
        await Assert.That(await PhonesAllowed(Alice)).IsTrue();
        Bought("token-1", Alice, state: "SUBSCRIPTION_STATE_ON_HOLD");
        await As(Alice, async s => { await s.SyncGoogle("token-1", revoked: false, CancellationToken.None); return 0; });
        await Assert.That(await PhonesAllowed(Alice)).IsFalse();

        // Paid again, then refunded: revoked even though Google's expiry is ahead.
        Bought("token-1", Alice, offer: null);
        await As(Alice, async s => { await s.SyncGoogle("token-1", revoked: false, CancellationToken.None); return 0; });
        await Assert.That(await PhonesAllowed(Alice)).IsTrue();
        await As(Alice, async s => { await s.SyncGoogle("token-1", revoked: true, CancellationToken.None); return 0; });
        await Assert.That((await Row("token-1"))!.State).IsEqualTo(SubscriptionState.Revoked);
        await Assert.That(await PhonesAllowed(Alice)).IsFalse();
    }

    [Test]
    public async Task A_pending_payment_that_clears_is_acknowledged_without_the_app()
    {
        // Paid in cash: the app links the purchase while it waits, and nothing is acknowledged yet.
        Bought("token-1", Alice, state: "SUBSCRIPTION_STATE_PENDING", offer: null, days: 30);
        await Link(Alice, "token-1");
        await Assert.That((await Row("token-1"))!.State).IsEqualTo(SubscriptionState.Pending);
        await Assert.That(Google.Acknowledged).IsEmpty();
        await Assert.That(await PhonesAllowed(Alice)).IsFalse();

        // The payment clears and Google says so: acknowledged then, or Google would refund it after 3 days.
        Bought("token-1", Alice, offer: null, days: 30);
        await As(Alice, async s => { await s.SyncGoogle("token-1", revoked: false, CancellationToken.None); return 0; });
        await Assert.That(Google.Acknowledged).IsEquivalentTo(["token-1"]);
        await Assert.That(await PhonesAllowed(Alice)).IsTrue();

        // Read again once acknowledged: nothing twice.
        Bought("token-1", Alice, offer: null, days: 30, acknowledged: true);
        await As(Alice, async s => { await s.SyncGoogle("token-1", revoked: false, CancellationToken.None); return 0; });
        await Assert.That(Google.Acknowledged.Count).IsEqualTo(1);
    }

    [Test]
    public async Task A_notification_about_a_token_nobody_linked_changes_nothing()
    {
        Bought("unlinked", Alice);

        await As(Alice, async s => { await s.SyncGoogle("unlinked", revoked: false, CancellationToken.None); return 0; });

        await Assert.That(await Row("unlinked")).IsNull();
        await Assert.That(Google.Read).IsEmpty();
    }

    [Test]
    public async Task A_cancelled_subscription_stays_paid_until_it_ends()
    {
        Bought("token-1", Alice, state: "SUBSCRIPTION_STATE_CANCELED", offer: null, days: 3);
        await Link(Alice, "token-1");
        await Assert.That(await PhonesAllowed(Alice)).IsTrue();

        Clock.Advance(TimeSpan.FromDays(4));
        await Assert.That(await PhonesAllowed(Alice)).IsFalse();
    }

    [Test]
    public async Task Phones_are_free_while_subscriptions_are_not_required()
    {
        await using var otherConnection = new SqliteConnection("DataSource=:memory:");
        otherConnection.Open();
        await using var free = BillingTestServices.Build(otherConnection, o => o.Required = false);

        await Assert.That(await free.GetRequiredService<MobileAccess>().AllowsPhonesAsync(Alice)).IsTrue();
    }

    [Test]
    public async Task A_free_account_has_its_phones_without_a_store_and_only_that_account()
    {
        await using var otherConnection = new SqliteConnection("DataSource=:memory:");
        otherConnection.Open();
        await using var withFree = BillingTestServices.Build(otherConnection, o => o.FreeAccounts = " ALICE-ID ;\nsomeone-else");
        withFree.GetRequiredService<TestCurrentUser>().UserId = Alice;
        await using (var scope = withFree.CreateAsyncScope())
        {
            var status = (await scope.ServiceProvider.GetRequiredService<Subscriptions>().Status(CancellationToken.None)).AsT1;
            await Assert.That(status.Required).IsTrue();
            await Assert.That(status.HasAccess).IsTrue();
            await Assert.That(status.FreeAccess).IsTrue();
            await Assert.That(status.State).IsNull(); // nothing bought: nothing in the store to show
        }
        await Assert.That(await withFree.GetRequiredService<MobileAccess>().AllowsPhonesAsync(Alice)).IsTrue();
        await Assert.That(await withFree.GetRequiredService<MobileAccess>().AllowsPhonesAsync(Bob)).IsFalse();

        withFree.GetRequiredService<TestCurrentUser>().UserId = Bob;
        await using (var scope = withFree.CreateAsyncScope())
        {
            var status = (await scope.ServiceProvider.GetRequiredService<Subscriptions>().Status(CancellationToken.None)).AsT1;
            await Assert.That(status.HasAccess).IsFalse();
            await Assert.That(status.FreeAccess).IsFalse();
        }
    }

    [Test]
    public async Task The_hourly_check_reads_again_what_ran_out_unheard_and_ends_fake_ones()
    {
        Bought("token-1", Alice, offer: null, days: 30);
        await Link(Alice, "token-1");
        await As(Bob, s => s.Fake(new FakePurchaseRequest(SubscriptionState.Active, 2), CancellationToken.None));
        Google.Read.Clear();

        Clock.Advance(TimeSpan.FromDays(3));
        await using (var scope = services.CreateAsyncScope())
            await scope.ServiceProvider.GetRequiredService<SubscriptionReconcileJob>().RunAsync(CancellationToken.None);
        await Assert.That(Google.Read).IsEmpty(); // Alice's month is not over

        Clock.Advance(TimeSpan.FromDays(28));
        Bought("token-1", Alice, state: "SUBSCRIPTION_STATE_EXPIRED", offer: null, days: -1);
        await using (var scope = services.CreateAsyncScope())
            await scope.ServiceProvider.GetRequiredService<SubscriptionReconcileJob>().RunAsync(CancellationToken.None);

        await Assert.That(Google.Read).IsEquivalentTo(["token-1"]);
        await Assert.That((await Row("token-1"))!.State).IsEqualTo(SubscriptionState.Expired);
        await Assert.That(await PhonesAllowed(Bob)).IsFalse();
    }

    [Test]
    public async Task An_erased_account_stops_renewing_and_leaves_nothing()
    {
        Bought("token-1", Alice, offer: null);
        await Link(Alice, "token-1");

        await using (var scope = services.CreateAsyncScope())
            await scope.ServiceProvider.GetRequiredService<BillingUserDataEraser>().EraseAsync(Alice);

        await Assert.That(Google.Cancelled).IsEquivalentTo(["token-1"]);
        await Assert.That(await Row("token-1")).IsNull();
        await Assert.That(await PhonesAllowed(Alice)).IsFalse();
    }

    [Test]
    public async Task Googles_push_is_read_from_its_envelope()
    {
        var json = """{"version":"1.0","packageName":"app.kadans","eventTimeMillis":"1","subscriptionNotification":{"version":"1.0","notificationType":12,"purchaseToken":"token-1","subscriptionId":"kadans_mobile"}}""";

        var notification = DeveloperNotification.Decode(Convert.ToBase64String(Encoding.UTF8.GetBytes(json)));

        await Assert.That(notification!.PackageName).IsEqualTo("app.kadans");
        await Assert.That(notification.SubscriptionNotification!.NotificationType).IsEqualTo(DeveloperNotification.Revoked);
        await Assert.That(notification.SubscriptionNotification.PurchaseToken).IsEqualTo("token-1");
        await Assert.That(DeveloperNotification.Decode("not base64!")).IsNull();
    }
}
