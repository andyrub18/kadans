using Google;
using Google.Apis.AndroidPublisher.v3;
using Google.Apis.AndroidPublisher.v3.Data;
using Google.Apis.Auth.OAuth2;
using Google.Apis.Services;
using Kadans.Modules.Billing.Domain;
using Microsoft.Extensions.Options;

namespace Kadans.Modules.Billing.Google;

/// <summary>What Google says about a purchase token (Play Developer API, <c>purchases.subscriptionsv2.get</c>).</summary>
internal sealed record GoogleSubscription(
    string State,
    string? ProductId,
    DateTimeOffset? ExpiresAt,
    bool AutoRenewing,
    string? OfferId,
    string? AccountHash,
    string? LinkedPurchaseToken,
    bool Acknowledged
);

/// <summary>The few Play Developer API calls Kadans makes. A seam for the tests, too.</summary>
internal interface IGooglePlay
{
    bool IsConfigured { get; }

    /// <summary>Null when Google does not know the token (a typo, another app's, or forged).</summary>
    Task<GoogleSubscription?> GetAsync(string purchaseToken, CancellationToken cancellationToken);

    /// <summary>Within 3 days of the purchase, or Google refunds it.</summary>
    Task AcknowledgeAsync(string productId, string purchaseToken, CancellationToken cancellationToken);

    /// <summary>Stops the renewal; paid time stays. For accounts being erased.</summary>
    Task CancelAsync(string productId, string purchaseToken, CancellationToken cancellationToken);
}

internal sealed class GooglePlayApi(IOptions<BillingOptions> options) : IGooglePlay
{
    private readonly GooglePlayOptions google = options.Value.Google;
    private AndroidPublisherService? service;

    public bool IsConfigured => google.IsConfigured;

    private AndroidPublisherService Service =>
        service ??= new AndroidPublisherService(new BaseClientService.Initializer
        {
#pragma warning disable CS0618 // FromJson/FromFile: the key comes from our own secret store, not from users.
            HttpClientInitializer = (string.IsNullOrWhiteSpace(google.ServiceAccountJson)
                    ? GoogleCredential.FromFile(google.ServiceAccountFile)
                    : GoogleCredential.FromJson(google.ServiceAccountJson))
                .CreateScoped(AndroidPublisherService.Scope.Androidpublisher),
#pragma warning restore CS0618
            ApplicationName = "Kadans",
        });

    public async Task<GoogleSubscription?> GetAsync(string purchaseToken, CancellationToken cancellationToken)
    {
        SubscriptionPurchaseV2 purchase;
        try
        {
            purchase = await Service.Purchases.Subscriptionsv2.Get(google.PackageName, purchaseToken).ExecuteAsync(cancellationToken);
        }
        catch (GoogleApiException e) when (e.HttpStatusCode is System.Net.HttpStatusCode.NotFound or System.Net.HttpStatusCode.BadRequest or System.Net.HttpStatusCode.Gone)
        {
            return null;
        }

        var items = purchase.LineItems ?? [];
        var latest = items.OrderByDescending(i => i.ExpiryTimeDateTimeOffset ?? DateTimeOffset.MinValue).FirstOrDefault();
        return new GoogleSubscription(
            purchase.SubscriptionState ?? "SUBSCRIPTION_STATE_UNSPECIFIED",
            latest?.ProductId,
            latest?.ExpiryTimeDateTimeOffset,
            items.Any(i => i.AutoRenewingPlan?.AutoRenewEnabled == true),
            latest?.OfferDetails?.OfferId,
            purchase.ExternalAccountIdentifiers?.ObfuscatedExternalAccountId,
            purchase.LinkedPurchaseToken,
            purchase.AcknowledgementState == "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED"
        );
    }

    public Task AcknowledgeAsync(string productId, string purchaseToken, CancellationToken cancellationToken) =>
        Service.Purchases.Subscriptions.Acknowledge(new SubscriptionPurchasesAcknowledgeRequest(), google.PackageName, productId, purchaseToken)
            .ExecuteAsync(cancellationToken);

    public Task CancelAsync(string productId, string purchaseToken, CancellationToken cancellationToken) =>
        Service.Purchases.Subscriptions.Cancel(google.PackageName, productId, purchaseToken).ExecuteAsync(cancellationToken);
}

/// <summary>Google's words for a subscription, in Kadans' (<see cref="SubscriptionState"/>).</summary>
internal static class GoogleStates
{
    public static SubscriptionState From(GoogleSubscription subscription, string trialOfferId) =>
        subscription.State switch
        {
            "SUBSCRIPTION_STATE_ACTIVE" => subscription.OfferId == trialOfferId ? SubscriptionState.Trial : SubscriptionState.Active,
            "SUBSCRIPTION_STATE_IN_GRACE_PERIOD" => SubscriptionState.GracePeriod,
            "SUBSCRIPTION_STATE_ON_HOLD" => SubscriptionState.OnHold,
            "SUBSCRIPTION_STATE_PAUSED" => SubscriptionState.Paused,
            "SUBSCRIPTION_STATE_CANCELED" => SubscriptionState.Canceled,
            "SUBSCRIPTION_STATE_EXPIRED" or "SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED" => SubscriptionState.Expired,
            _ => SubscriptionState.Pending,
        };
}
