using Kadans.Modules.Notifications.Dispatch;
using Kadans.Modules.Notifications.Features;
using Kadans.Modules.Notifications.Persistence;
using Kadans.Modules.Notifications.Push;
using Kadans.Modules.Notifications.Realtime;
using Kadans.SharedKernel.Modules;
using Kadans.SharedKernel.Persistence;
using Kadans.SharedKernel.Notifications;
using Kadans.SharedKernel.Realtime;
using Kadans.SharedKernel.Users;
using Microsoft.EntityFrameworkCore;
using Quartz;
using System.Text.Json.Serialization;

namespace Kadans.Modules.Notifications;

/// <summary>Notification log, push (FCM) and the SignalR hub. Owns the <c>notifications</c> schema.</summary>
public sealed class NotificationsModule : IModule
{
    public void AddServices(IServiceCollection services, IConfiguration configuration)
    {
        var connectionString = KadansDatabase.ConnectionString(configuration);
        services.AddDbContext<NotificationsDbContext>(options =>
            options.UseNpgsql(
                connectionString,
                npgsql => npgsql.MigrationsHistoryTable("__ef_migrations_history", NotificationsDbContext.Schema)
            ),
            optionsLifetime: ServiceLifetime.Singleton // built once: the options depend on configuration only
        );

        // Hub payloads must match the REST contract (string enums), or clients need two decoders.
        services.AddSignalR().AddJsonProtocol(json =>
            json.PayloadSerializerOptions.Converters.Add(new JsonStringEnumConverter())
        );
        services.AddSingleton<IRealtimePublisher, SignalRRealtimePublisher>();
        services.AddSingleton<HubConnections>();
        services.AddSingleton<ISessionEndListener>(provider => provider.GetRequiredService<HubConnections>());

        var pushSection = configuration.GetSection(PushOptions.SectionName);
        services.Configure<PushOptions>(pushSection);
        if (string.Equals(pushSection["Provider"], "Fcm", StringComparison.OrdinalIgnoreCase))
            services.AddSingleton<IPushSender, FcmPushSender>();
        else if (string.Equals(pushSection["Provider"], "Simulated", StringComparison.OrdinalIgnoreCase))
            services.AddSingleton<IPushSender, SimulatedPushSender>(); // load tests: ProductionConfiguration refuses it elsewhere
        else
            services.AddSingleton<IPushSender, LoggingPushSender>();

        services.AddSingleton<PushMetrics>();
        services.AddSingleton<PushQueue>();
        services.AddHostedService<PushWorker>();
        services.AddScoped<INotificationDispatcher, NotificationDispatcher>();
        services.AddScoped<NotificationQueries>();
        services.AddScoped<IUserDataEraser, NotificationsUserDataEraser>();

        services.Configure<NotificationsOptions>(configuration.GetSection(NotificationsOptions.SectionName));
        services.AddQuartz(quartz =>
        {
            // Nightly, and once soon after a start so a server that restarts often still cleans up.
            quartz.AddJob<NotificationsRetentionJob>(job => job.WithIdentity(NotificationsRetentionJob.Key));
            quartz.AddTrigger(trigger =>
                trigger
                    .ForJob(NotificationsRetentionJob.Key)
                    .WithIdentity("notifications-retention-nightly", "notifications")
                    .WithCronSchedule(Retention.NightlyCron, cron => cron.InTimeZone(TimeZoneInfo.Utc))
            );
            quartz.AddTrigger(trigger =>
                trigger
                    .ForJob(NotificationsRetentionJob.Key)
                    .WithIdentity("notifications-retention-startup", "notifications")
                    .StartAt(DateBuilder.FutureDate(2, IntervalUnit.Minute))
            );
        });
    }

    public Task InitializeAsync(IServiceProvider services, CancellationToken cancellationToken) =>
        services.MigrateIfConfiguredAsync<NotificationsDbContext>(cancellationToken);

    public void MapEndpoints(IEndpointRouteBuilder endpoints)
    {
        endpoints.MapNotificationRoutes();
        // A connection is authorized once, when it opens: it closes when that token expires (and at once when its
        // session ends, see HubConnections). The app reconnects with a fresh token and catches up on what it missed.
        endpoints.MapHub<KadansHub>(RealtimeHub.Path, options => options.CloseOnAuthenticationExpiration = true).RequireAuthorization();
    }
}
