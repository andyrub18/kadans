using System.Text.Json;
using Kadans.Modules.Notifications.Contracts;
using Kadans.Modules.Notifications.Domain;
using Kadans.Modules.Notifications.Persistence;
using Kadans.Modules.Notifications.Push;
using Kadans.SharedKernel.Notifications;
using Kadans.SharedKernel.Realtime;
using Kadans.SharedKernel.Users;

namespace Kadans.Modules.Notifications.Dispatch;

/// <summary>
/// Stores the notification, then fans out: live event to connected clients, push to registered
/// devices. Channel failures are logged, never propagated – the caller has already decided the
/// notification is due.
/// </summary>
internal sealed class NotificationDispatcher(
    NotificationsDbContext dbContext,
    IRealtimePublisher realtime,
    IPushSender push,
    IDevicePushTargets devices,
    ILogger<NotificationDispatcher> logger
) : INotificationDispatcher
{
    public async Task DispatchAsync(string userId, NotificationMessage message, CancellationToken cancellationToken = default)
    {
        var notification = new Notification
        {
            UserId = userId,
            Kind = message.Kind,
            Title = message.Title,
            Body = message.Body,
            DataJson = message.Data is null ? null : JsonSerializer.Serialize(message.Data),
        };
        dbContext.Notifications.Add(notification);
        await dbContext.SaveChangesAsync(cancellationToken);

        var response = notification.ToResponse();

        try
        {
            await realtime.PublishToUserAsync(userId, "notification", response, cancellationToken);
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Realtime publish failed for user {UserId}", userId);
        }

        try
        {
            var targets = await devices.ForUserAsync(userId, cancellationToken);
            if (targets.Count > 0)
            {
                var dead = await push.SendAsync(targets, message, cancellationToken);
                foreach (var token in dead)
                    await devices.InvalidateAsync(token, cancellationToken);
            }
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Push failed for user {UserId}", userId);
        }
    }
}

internal static class NotificationMappings
{
    extension(Notification notification)
    {
        public NotificationResponse ToResponse() =>
            new(
                notification.Id,
                notification.Kind,
                notification.Title,
                notification.Body,
                notification.DataJson is null ? null : JsonSerializer.Deserialize<Dictionary<string, string>>(notification.DataJson),
                notification.CreatedAt,
                notification.ReadAt
            );
    }
}
