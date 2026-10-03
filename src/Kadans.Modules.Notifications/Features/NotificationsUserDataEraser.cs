using Kadans.Modules.Notifications.Persistence;
using Kadans.SharedKernel.Persistence;
using Kadans.SharedKernel.Users;

namespace Kadans.Modules.Notifications.Features;

/// <summary>An account being erased: its whole notification centre.</summary>
internal sealed class NotificationsUserDataEraser(NotificationsDbContext dbContext) : IUserDataEraser
{
    public Task EraseAsync(string userId, CancellationToken cancellationToken = default) =>
        Retention.DeleteInBatchesAsync(dbContext.Notifications.Where(n => n.UserId == userId), n => n.Id, cancellationToken);
}
