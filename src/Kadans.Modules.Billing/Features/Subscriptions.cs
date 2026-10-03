using Kadans.Modules.Billing.Contracts;
using Kadans.Modules.Billing.Domain;
using Kadans.Modules.Billing.Google;
using Kadans.Modules.Billing.Persistence;
using Kadans.SharedKernel.Errors;
using Kadans.SharedKernel.Security;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using OneOf;

namespace Kadans.Modules.Billing.Features;

/// <summary>
/// The account's subscriptions, as the stores report them. The app's word is never enough: it hands over a purchase,
/// and the state comes from the store (ARCHITECTURE → Subscriptions).
/// </summary>
internal sealed class Subscriptions(
    BillingDbContext dbContext,
    IGooglePlay google,
    MobileAccess access,
    ICurrentUserService currentUser,
    IOptions<BillingOptions> options,
    TimeProvider time,
    ILogger<Subscriptions> logger
)
{
    private BillingOptions Settings => options.Value;

    public async Task<OneOf<ApplicationError, SubscriptionStatusResponse>> Status(CancellationToken cancellationToken)
    {
        if (currentUser.UserId is not { } userId)
            return Unauthorized();
        return await StatusOf(userId, cancellationToken);
    }

    /// <summary>A Google purchase this account made (or restores): checked with Google, acknowledged, and kept.</summary>
    public async Task<OneOf<ApplicationError, SubscriptionStatusResponse>> LinkGoogle(GooglePurchaseRequest request, CancellationToken cancellationToken)
    {
        if (currentUser.UserId is not { } userId)
            return Unauthorized();
        if (!google.IsConfigured)
            return new ApplicationError(ErrorTypes.BillingUnavailable, "Subscriptions are not available yet.");

        var purchase = string.IsNullOrWhiteSpace(request.PurchaseToken) ? null : await google.GetAsync(request.PurchaseToken, cancellationToken);
        if (purchase is null || purchase.ProductId != Settings.Google.ProductId)
            return new ApplicationError(ErrorTypes.PurchaseNotVerified, "This purchase could not be verified.");

        // Only ever this account's: the app names the account in the purchase, and a token is linked once.
        var existing = await dbContext.Subscriptions.IgnoreQueryFilters()
            .FirstOrDefaultAsync(s => s.Store == BillingStore.Google && s.StoreKey == request.PurchaseToken, cancellationToken);
        if (purchase.AccountHash != AccountHash.Of(userId) || (existing is not null && existing.UserId != userId))
        {
            logger.LogWarning("User {UserId} presented a Google purchase made for another account", userId);
            return new ApplicationError(ErrorTypes.PurchaseOtherAccount, "This subscription belongs to another Kadans account.");
        }

        var now = time.GetUtcNow();
        var subscription = existing ?? dbContext.Subscriptions.Add(new StoreSubscription
        {
            UserId = userId,
            Store = BillingStore.Google,
            StoreKey = request.PurchaseToken,
            ProductId = purchase.ProductId!,
            CreatedAt = now,
        }).Entity;
        Apply(subscription, purchase, revoked: false, now);

        // A resubscription or a plan change replaces an older purchase: that one is over.
        if (purchase.LinkedPurchaseToken is { } replaced)
        {
            await dbContext.Subscriptions.IgnoreQueryFilters()
                .Where(s => s.Store == BillingStore.Google && s.StoreKey == replaced && s.UserId == userId)
                .ExecuteUpdateAsync(s => s.SetProperty(x => x.State, SubscriptionState.Expired).SetProperty(x => x.UpdatedAt, now), cancellationToken);
        }

        await dbContext.SaveChangesAsync(cancellationToken);
        if (!purchase.Acknowledged && subscription.State != SubscriptionState.Pending)
            await google.AcknowledgeAsync(subscription.ProductId, request.PurchaseToken, cancellationToken);

        access.Changed(userId);
        logger.LogInformation("User {UserId} linked a Google subscription: {State} until {ExpiresAt:O}", userId, subscription.State, subscription.ExpiresAt);
        return await StatusOf(userId, cancellationToken);
    }

    /// <summary>
    /// Google said something changed about a token (a real-time notification), or a paid period ran out unheard: read
    /// it again. A token no account has linked yet is left for the app, which links it right after the purchase. A
    /// pending payment that clears is acknowledged here: the app may not open again within Google's 3 days.
    /// </summary>
    public async Task SyncGoogle(string purchaseToken, bool revoked, CancellationToken cancellationToken)
    {
        var subscription = await dbContext.Subscriptions.IgnoreQueryFilters()
            .FirstOrDefaultAsync(s => s.Store == BillingStore.Google && s.StoreKey == purchaseToken, cancellationToken);
        if (subscription is null)
            return;

        var now = time.GetUtcNow();
        var purchase = await google.GetAsync(purchaseToken, cancellationToken);
        if (purchase is null)
        {
            subscription.State = SubscriptionState.Expired;
            subscription.UpdatedAt = now;
        }
        else
        {
            Apply(subscription, purchase, revoked, now);
        }

        await dbContext.SaveChangesAsync(cancellationToken);
        if (purchase is { Acknowledged: false } && subscription.GivesAccess(now))
            await google.AcknowledgeAsync(subscription.ProductId, purchaseToken, cancellationToken);
        access.Changed(subscription.UserId);
    }

    /// <summary>Development only: a subscription without a store, to try the paywall and the gated reminders.</summary>
    public async Task<OneOf<ApplicationError, SubscriptionStatusResponse>> Fake(FakePurchaseRequest request, CancellationToken cancellationToken)
    {
        if (currentUser.UserId is not { } userId)
            return Unauthorized();

        var now = time.GetUtcNow();
        await dbContext.Subscriptions.Where(s => s.Store == BillingStore.Fake).ExecuteDeleteAsync(cancellationToken);
        dbContext.Subscriptions.Add(new StoreSubscription
        {
            UserId = userId,
            Store = BillingStore.Fake,
            StoreKey = $"fake-{Guid.NewGuid()}",
            ProductId = Settings.Google.ProductId,
            State = request.State,
            ExpiresAt = now.AddDays(request.Days),
            AutoRenewing = request.State is SubscriptionState.Trial or SubscriptionState.Active,
            CreatedAt = now,
            UpdatedAt = now,
        });
        await dbContext.SaveChangesAsync(cancellationToken);
        access.Changed(userId);
        return await StatusOf(userId, cancellationToken);
    }

    private void Apply(StoreSubscription subscription, GoogleSubscription purchase, bool revoked, DateTimeOffset now)
    {
        subscription.State = revoked ? SubscriptionState.Revoked : GoogleStates.From(purchase, Settings.Google.TrialOfferId);
        subscription.ExpiresAt = purchase.ExpiresAt;
        subscription.AutoRenewing = purchase.AutoRenewing && !revoked;
        if (purchase.ProductId is { } product)
            subscription.ProductId = product;
        subscription.UpdatedAt = now;
    }

    private async Task<SubscriptionStatusResponse> StatusOf(string userId, CancellationToken cancellationToken)
    {
        var now = time.GetUtcNow();
        var all = await dbContext.Subscriptions.IgnoreQueryFilters().AsNoTracking().Where(s => s.UserId == userId).ToListAsync(cancellationToken);
        // The one that matters: paid up first, then the latest to end.
        var best = all.OrderByDescending(s => s.GivesAccess(now)).ThenByDescending(s => s.ExpiresAt).FirstOrDefault();
        var free = await dbContext.FreeAccounts.IgnoreQueryFilters().AnyAsync(f => f.UserId == userId, cancellationToken);
        return new SubscriptionStatusResponse(
            Settings.Required,
            !Settings.Required || free || all.Any(s => s.GivesAccess(now)),
            best?.State,
            best?.Store,
            best?.ExpiresAt,
            best?.AutoRenewing ?? false,
            AccountHash.Of(userId),
            Settings.Google.ProductId,
            Settings.FakeStore.Enabled,
            free
        );
    }

    private static ApplicationError Unauthorized() => new(ErrorTypes.Unauthorized, "Unable to resolve current user.");
}
