using Kadans.Modules.Notifications.Dispatch;
using Kadans.Modules.Notifications.Features;
using Kadans.Modules.Notifications.Persistence;
using Kadans.Modules.Notifications.Push;
using Kadans.Modules.Notifications.Realtime;
using Kadans.SharedKernel.Modules;
using Kadans.SharedKernel.Notifications;
using Kadans.SharedKernel.Realtime;
using Microsoft.EntityFrameworkCore;

namespace Kadans.Modules.Notifications;

/// <summary>Notification log, push (FCM) and the SignalR hub. Owns the <c>notifications</c> schema.</summary>
public sealed class NotificationsModule : IModule
{
    public void AddServices(IServiceCollection services, IConfiguration configuration)
    {
        services.AddDbContext<NotificationsDbContext>(options =>
            options.UseNpgsql(
                configuration.GetConnectionString("kadans"),
                npgsql => npgsql.MigrationsHistoryTable("__ef_migrations_history", NotificationsDbContext.Schema)
            )
        );

        services.AddSignalR();
        services.AddSingleton<IRealtimePublisher, SignalRRealtimePublisher>();

        var pushSection = configuration.GetSection(PushOptions.SectionName);
        services.Configure<PushOptions>(pushSection);
        if (string.Equals(pushSection["Provider"], "Fcm", StringComparison.OrdinalIgnoreCase))
            services.AddSingleton<IPushSender, FcmPushSender>();
        else
            services.AddSingleton<IPushSender, LoggingPushSender>();

        services.AddScoped<INotificationDispatcher, NotificationDispatcher>();
        services.AddScoped<NotificationQueries>();
    }

    public void MapEndpoints(IEndpointRouteBuilder endpoints)
    {
        endpoints.MapNotificationRoutes();
        endpoints.MapHub<KadansHub>(RealtimeHub.Path).RequireAuthorization();
    }
}
