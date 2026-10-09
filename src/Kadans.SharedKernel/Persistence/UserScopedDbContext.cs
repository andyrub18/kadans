using Kadans.SharedKernel.Security;
using Microsoft.EntityFrameworkCore;

namespace Kadans.SharedKernel.Persistence;

/// <summary>
/// A module's DbContext whose global query filters let through the current user's rows only (per-user isolation,
/// CLAUDE.md). Contexts come from EF Core's pool: building one with its internal services on every request was about a
/// tenth of the API's CPU (docs/LOADTEST.md). A pooled instance outlives its request, so the user cannot be a
/// constructor argument: each scope's instance is handed that scope's <see cref="ICurrentUserService"/> when it is
/// rented (<see cref="UserScopedDbContexts.AddUserScopedDbContextPool{TContext}"/>) and loses it when it goes back. A
/// context rented any other way has no user and sees no one's rows, never the previous renter's.
/// </summary>
public abstract class UserScopedDbContext(DbContextOptions options) : DbContext(options)
{
    /// <summary>Whose rows the filters let through: set on every rent, cleared on return.</summary>
    internal ICurrentUserService? CurrentUser { get; set; }

    /// <summary>For the query filters (<c>x =&gt; x.UserId == CurrentUserId</c>): read each time a query runs.</summary>
    protected string? CurrentUserId => CurrentUser?.UserId;

    public override void Dispose()
    {
        CurrentUser = null;
        base.Dispose();
    }

    public override ValueTask DisposeAsync()
    {
        CurrentUser = null;
        return base.DisposeAsync();
    }
}

public static class UserScopedDbContexts
{
    /// <summary>
    /// The context from a pool, scoped as <c>AddDbContext</c> would register it, each scope's instance seeing that
    /// scope's user: requests their caller, jobs no one (they read across users with <c>IgnoreQueryFilters</c>).
    /// <paramref name="onRent"/> hands it anything else it needs, from singletons: a scoped service would outlive its
    /// scope in a pooled context.
    /// </summary>
    public static IServiceCollection AddUserScopedDbContextPool<TContext>(
        this IServiceCollection services,
        Action<DbContextOptionsBuilder> options,
        Action<IServiceProvider, TContext>? onRent = null
    )
        where TContext : UserScopedDbContext
    {
        services.AddPooledDbContextFactory<TContext>(options);
        services.AddScoped(provider =>
        {
            var context = provider.GetRequiredService<IDbContextFactory<TContext>>().CreateDbContext();
            context.CurrentUser = provider.GetRequiredService<ICurrentUserService>();
            onRent?.Invoke(provider, context);
            return context;
        });
        return services;
    }
}
