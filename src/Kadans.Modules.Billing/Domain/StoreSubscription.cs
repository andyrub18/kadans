namespace Kadans.Modules.Billing.Domain;

public enum BillingStore
{
    Google,
    Apple,
    /// <summary>Development only: purchases that never touched a store (<c>Billing:FakeStore:Enabled</c>).</summary>
    Fake,
}

/// <summary>Where a store says a subscription is. Only the store moves it; Kadans reads it back.</summary>
public enum SubscriptionState
{
    /// <summary>Bought, payment not through yet (a cash or bank payment, in some countries).</summary>
    Pending,
    /// <summary>The 14-day free trial.</summary>
    Trial,
    Active,
    /// <summary>A renewal failed and the store is retrying: still paid up for a few days.</summary>
    GracePeriod,
    /// <summary>The retries failed; no access until the payment is fixed (Google's account hold).</summary>
    OnHold,
    /// <summary>Paused by the person (Google), until it resumes.</summary>
    Paused,
    /// <summary>Will not renew, but paid up until <see cref="StoreSubscription.ExpiresAt"/>.</summary>
    Canceled,
    Expired,
    /// <summary>Refunded or taken back by the store: no access, even before its expiry.</summary>
    Revoked,
}

/// <summary>
/// One store subscription and the account it belongs to. An account may hold several (an Android one and an iPhone
/// one); a purchase belongs to one account only. Its <see cref="StoreKey"/> is the store's own handle: Google's purchase
/// token, Apple's original transaction id.
/// </summary>
internal sealed class StoreSubscription
{
    public Guid Id { get; init; } = Guid.CreateVersion7();
    public required string UserId { get; init; }
    public BillingStore Store { get; init; }
    public required string StoreKey { get; init; }
    public required string ProductId { get; set; }
    public SubscriptionState State { get; set; }
    public DateTimeOffset? ExpiresAt { get; set; }
    public bool AutoRenewing { get; set; }
    public DateTimeOffset CreatedAt { get; init; }
    public DateTimeOffset UpdatedAt { get; set; }

    /// <summary>
    /// Paid up now. A grace period still counts (the store is retrying); a canceled subscription counts until its
    /// expiry; on hold, paused, expired or revoked do not.
    /// </summary>
    public bool GivesAccess(DateTimeOffset now) =>
        State switch
        {
            SubscriptionState.Trial or SubscriptionState.Active or SubscriptionState.GracePeriod => true,
            SubscriptionState.Canceled => ExpiresAt is { } end && end > now,
            _ => false,
        };
}
