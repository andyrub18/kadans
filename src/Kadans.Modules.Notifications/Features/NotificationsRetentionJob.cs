using Kadans.Modules.Notifications.Persistence;
using Kadans.SharedKernel.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Quartz;

namespace Kadans.Modules.Notifications.Features;

/// <summary>
/// Nightly: the notification centre keeps the last <see cref="NotificationsOptions.RetentionDays"/> days. A reminder
/// is a notification, so a reminder every 5 minutes leaves 288 a day; what they said lives on in the todos.
/// </summary>
[DisallowConcurrentExecution]
internal sealed class NotificationsRetentionJob(
    NotificationsDbContext dbContext,
    IOptions<NotificationsOptions> options,
    ILogger<NotificationsRetentionJob> logger
) : IJob
{
    public static readonly JobKey Key = new("notifications-retention", "notifications");

    public Task Execute(IJobExecutionContext context) => RunAsync(DateTimeOffset.UtcNow, context.CancellationToken);

    internal async Task RunAsync(DateTimeOffset now, CancellationToken cancellationToken)
    {
        var days = options.Value.RetentionDays;
        var cutoff = now.AddDays(-days);
        var removed = await Retention.DeleteInBatchesAsync(
            dbContext.Notifications.Where(n => n.CreatedAt < cutoff),
            n => n.Id,
            cancellationToken
        );
        logger.LogInformation("Retention: removed {Count} notification(s) older than {Days} days", removed, days);
    }
}
