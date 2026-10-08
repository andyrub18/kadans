using System.Text.Json;
using Kadans.Modules.Notifications.Contracts;
using Kadans.Modules.Notifications.Domain;
using Kadans.Modules.Notifications.Persistence;
using Kadans.Modules.Notifications.Push;
using Kadans.SharedKernel.Notifications;
using Kadans.SharedKernel.Realtime;

namespace Kadans.Modules.Notifications.Dispatch;

/// <summary>
/// Stores the notification, then fans out: live event to connected clients now, push to registered
/// devices from a background queue (the provider call is slow and nobody should wait for it).
/// Channel failures are logged, never propagated – the caller has already decided the
/// notification is due.
/// </summary>
internal sealed class NotificationDispatcher(
    NotificationsDbContext dbContext,
    IRealtimePublisher realtime,
    PushQueue pushQueue,
    ILogger<NotificationDispatcher> logger
) : INotificationDispatcher
{
    public Task DispatchAsync(string userId, NotificationMessage message, CancellationToken cancellationToken = default) =>
        DispatchManyAsync([new UserNotification(userId, message)], cancellationToken);

    public async Task DispatchManyAsync(IReadOnlyList<UserNotification> notifications, CancellationToken cancellationToken = default)
    {
        var stored = notifications
            .Select(n => new Notification
            {
                UserId = n.UserId,
                Kind = n.Message.Kind,
                Title = n.Message.Title,
                Body = n.Message.Body,
                DataJson = n.Message.Data is null ? null : JsonSerializer.Serialize(n.Message.Data),
            })
            .ToList();
        dbContext.Notifications.AddRange(stored);
        await dbContext.SaveChangesAsync(cancellationToken);

        for (var i = 0; i < stored.Count; i++)
        {
            var userId = notifications[i].UserId;
            try
            {
                await realtime.PublishToUserAsync(userId, "notification", stored[i].ToResponse(), cancellationToken);
            }
            catch (Exception ex)
            {
                logger.LogError(ex, "Realtime publish failed for user {UserId}", userId);
            }

            pushQueue.Enqueue(new PushRequest(userId, notifications[i].Message));
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
