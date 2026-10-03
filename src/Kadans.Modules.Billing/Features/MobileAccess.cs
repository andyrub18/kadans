using System.Collections.Concurrent;
using Kadans.Modules.Billing.Persistence;
using Kadans.SharedKernel.Users;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace Kadans.Modules.Billing.Features;

/// <summary>
/// "May this account use a phone?", asked before every push to a phone: from memory, read at most once a minute per
/// account, dropped the moment a subscription changes (Kadans runs as one instance). Always yes while subscriptions
/// are not required.
/// </summary>
internal sealed class MobileAccess(IServiceScopeFactory scopes, IOptions<BillingOptions> options, TimeProvider time) : IMobileAccess
{
    internal static readonly TimeSpan CacheFor = TimeSpan.FromMinutes(1);
    private readonly ConcurrentDictionary<string, (bool Allowed, DateTimeOffset Until)> answers = new();

    public async Task<bool> AllowsPhonesAsync(string userId, CancellationToken cancellationToken = default)
    {
        if (!options.Value.Required)
            return true;

        var now = time.GetUtcNow();
        if (answers.TryGetValue(userId, out var cached) && cached.Until > now)
            return cached.Allowed;

        await using var scope = scopes.CreateAsyncScope();
        var subscriptions = await scope.ServiceProvider.GetRequiredService<BillingDbContext>()
            .Subscriptions.IgnoreQueryFilters().AsNoTracking().Where(s => s.UserId == userId).ToListAsync(cancellationToken);
        var allowed = subscriptions.Any(s => s.GivesAccess(now));
        answers[userId] = (allowed, now + CacheFor);
        return allowed;
    }

    /// <summary>A subscription of this account changed: the next question reads it again.</summary>
    public void Changed(string userId) => answers.TryRemove(userId, out _);
}
