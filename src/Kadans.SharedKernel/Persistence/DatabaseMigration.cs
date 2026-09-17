using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace Kadans.SharedKernel.Persistence;

/// <summary>
/// Production applies pending migrations when the API starts (<c>Database:MigrateOnStartup=true</c>, set by
/// the container image): Kadans runs as exactly one instance, so there is no race between replicas,
/// and a deploy is then a single step – start the new image. Development keeps the explicit
/// <c>dotnet ef database update</c>, so a half-written migration is never applied by accident.
/// </summary>
public static class DatabaseMigration
{
    public const string ConfigurationKey = "Database:MigrateOnStartup";

    public static async Task MigrateIfConfiguredAsync<TContext>(this IServiceProvider services, CancellationToken cancellationToken)
        where TContext : DbContext
    {
        if (!services.GetRequiredService<IConfiguration>().GetValue<bool>(ConfigurationKey))
            return;

        await using var scope = services.CreateAsyncScope();
        var context = scope.ServiceProvider.GetRequiredService<TContext>();
        var logger = scope.ServiceProvider.GetRequiredService<ILoggerFactory>().CreateLogger(typeof(DatabaseMigration));

        var pending = (await context.Database.GetPendingMigrationsAsync(cancellationToken)).ToList();
        if (pending.Count == 0)
        {
            logger.LogInformation("{Context}: schema is up to date", typeof(TContext).Name);
            return;
        }

        logger.LogInformation("{Context}: applying {Count} migration(s): {Migrations}", typeof(TContext).Name, pending.Count, string.Join(", ", pending));
        await context.Database.MigrateAsync(cancellationToken);
    }
}
