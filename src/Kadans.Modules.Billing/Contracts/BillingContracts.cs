using Kadans.Modules.Billing.Domain;

namespace Kadans.Modules.Billing.Contracts;

/// <summary>
/// What the app needs to decide on a paywall, and to start a purchase.
/// </summary>
/// <param name="Required">Phones need a subscription. False until the store product is live: no paywall then.</param>
/// <param name="HasAccess">Paid up now (a trial, a grace period and a cancelled-but-paid month count).</param>
/// <param name="AccountHash">What the app hands the store with a purchase, so it can only ever be this account's.</param>
/// <param name="FakeStore">Development: purchases can be made without a store (<c>POST /billing/fake/purchases</c>).</param>
/// <param name="FreeAccess">This account's phones are free (an admin listed it): access without a subscription.</param>
public sealed record SubscriptionStatusResponse(
    bool Required,
    bool HasAccess,
    SubscriptionState? State,
    BillingStore? Store,
    DateTimeOffset? ExpiresAt,
    bool AutoRenewing,
    string AccountHash,
    string GoogleProductId,
    bool FakeStore,
    bool FreeAccess = false
);

/// <summary>A purchase the app just made (or found again: "Restore purchases"), for the server to check with Google.</summary>
public sealed record GooglePurchaseRequest(string PurchaseToken);

/// <summary>Development only: a subscription in this state, ending in that many days.</summary>
public sealed record FakePurchaseRequest(SubscriptionState State = SubscriptionState.Trial, int Days = 14);

/// <summary>Admin: make an account's phones free. <paramref name="Account"/> is its username, or its confirmed email address.</summary>
public sealed record AddFreeAccountRequest(string Account);

/// <summary>An account whose phones are free. Username and email are null when the account is gone.</summary>
public sealed record FreeAccountResponse(
    string UserId,
    string? Username,
    string? Email,
    bool EmailConfirmed,
    string? DisplayName,
    DateTimeOffset AddedAt
);
