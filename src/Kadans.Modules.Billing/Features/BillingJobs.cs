using Kadans.Modules.Billing.Domain;
using Kadans.Modules.Billing.Google;
using Kadans.Modules.Billing.Persistence;
using Kadans.SharedKernel.Users;
using Microsoft.EntityFrameworkCore;
using Quartz;

namespace Kadans.Modules.Billing.Features;

/// <summary>
/// Hourly, in case a notification went missing: a subscription still counted as paid whose period ran out is read
/// again from its store. Development's fake subscriptions simply expire.
/// </summary>
[DisallowConcurrentExecution]
internal sealed class SubscriptionReconcileJob(
    BillingDbContext dbContext,
    Subscriptions subscriptions,
    IGooglePlay google,
    MobileAccess access,
    TimeProvider time,
    ILogger<SubscriptionReconcileJob> logger
) : IJob
{
    public static readonly JobKey Key = new("subscription-reconcile", "billing");

    public Task Execute(IJobExecutionContext context) => RunAsync(context.CancellationToken);

    internal async Task RunAsync(CancellationToken cancellationToken)
    {
        var now = time.GetUtcNow();
        var lapsed = await dbContext.Subscriptions.IgnoreQueryFilters()
            .Where(s => (s.State == SubscriptionState.Trial || s.State == SubscriptionState.Active || s.State == SubscriptionState.GracePeriod
                    || s.State == SubscriptionState.Canceled) && s.ExpiresAt < now)
            .OrderBy(s => s.ExpiresAt)
            .Take(500)
            .ToListAsync(cancellationToken);

        foreach (var subscription in lapsed)
        {
            if (subscription.Store == BillingStore.Google && google.IsConfigured)
            {
                try
                {
                    await subscriptions.SyncGoogle(subscription.StoreKey, revoked: false, cancellationToken);
                }
                catch (Exception ex) when (ex is not OperationCanceledException)
                {
                    logger.LogWarning(ex, "Could not read subscription {Id} again from Google", subscription.Id);
                }
            }
            else if (subscription.Store == BillingStore.Fake)
            {
                subscription.State = SubscriptionState.Expired;
                subscription.UpdatedAt = now;
                await dbContext.SaveChangesAsync(cancellationToken);
                access.Changed(subscription.UserId);
            }
        }

        if (lapsed.Count > 0)
            logger.LogInformation("Billing: re-read {Count} subscription(s) whose paid period ran out", lapsed.Count);
    }
}

/// <summary>
/// An account being erased: a Google subscription still renewing is cancelled first (the store would keep billing a
/// deleted account), then every record goes. Apple's cannot be cancelled by Kadans: the emails tell the person.
/// </summary>
internal sealed class BillingUserDataEraser(BillingDbContext dbContext, IGooglePlay google, MobileAccess access, ILogger<BillingUserDataEraser> logger)
    : IUserDataEraser
{
    public async Task EraseAsync(string userId, CancellationToken cancellationToken = default)
    {
        var renewing = await dbContext.Subscriptions.IgnoreQueryFilters()
            .Where(s => s.UserId == userId && s.Store == BillingStore.Google && s.AutoRenewing)
            .ToListAsync(cancellationToken);
        foreach (var subscription in renewing.Where(_ => google.IsConfigured))
        {
            try
            {
                await google.CancelAsync(subscription.ProductId, subscription.StoreKey, cancellationToken);
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                logger.LogWarning(ex, "Could not cancel the Google subscription of erased account {UserId}", userId);
            }
        }

        await dbContext.Subscriptions.IgnoreQueryFilters().Where(s => s.UserId == userId).ExecuteDeleteAsync(cancellationToken);
        await dbContext.FreeAccounts.IgnoreQueryFilters().Where(f => f.UserId == userId).ExecuteDeleteAsync(cancellationToken);
        access.Changed(userId);
    }
}
