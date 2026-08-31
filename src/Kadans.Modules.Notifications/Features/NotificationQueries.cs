using Kadans.Modules.Notifications.Contracts;
using Kadans.Modules.Notifications.Dispatch;
using Kadans.Modules.Notifications.Persistence;
using Kadans.SharedKernel.Errors;
using Kadans.SharedKernel.Security;
using Microsoft.EntityFrameworkCore;
using OneOf;
using OneOf.Types;

namespace Kadans.Modules.Notifications.Features;

internal sealed class NotificationQueries(NotificationsDbContext dbContext, ICurrentUserService currentUser)
{
    public async Task<OneOf<ApplicationError, List<NotificationResponse>>> List(bool unreadOnly, int page, int pageSize)
    {
        if (currentUser.UserId is null)
            return Unauthorized();

        var items = await dbContext
            .Notifications.Where(n => n.UserId == currentUser.UserId && (!unreadOnly || n.ReadAt == null))
            .OrderByDescending(n => n.CreatedAt)
            .Skip((page - 1) * pageSize)
            .Take(pageSize)
            .ToListAsync();

        return items.ConvertAll(n => n.ToResponse());
    }

    public async Task<OneOf<ApplicationError, UnreadCountResponse>> UnreadCount()
    {
        if (currentUser.UserId is null)
            return Unauthorized();

        return new UnreadCountResponse(await dbContext.Notifications.CountAsync(n => n.UserId == currentUser.UserId && n.ReadAt == null));
    }

    public async Task<OneOf<ApplicationError, Success>> MarkRead(Guid id)
    {
        if (currentUser.UserId is null)
            return Unauthorized();

        var updated = await dbContext
            .Notifications.Where(n => n.Id == id && n.UserId == currentUser.UserId && n.ReadAt == null)
            .ExecuteUpdateAsync(s => s.SetProperty(n => n.ReadAt, DateTimeOffset.UtcNow));

        if (updated == 0 && !await dbContext.Notifications.AnyAsync(n => n.Id == id && n.UserId == currentUser.UserId))
            return new ApplicationError(ErrorTypes.NotificationNotFound, $"Notification {id} not found.");

        return new Success();
    }

    public async Task<OneOf<ApplicationError, Success>> MarkAllRead()
    {
        if (currentUser.UserId is null)
            return Unauthorized();

        await dbContext
            .Notifications.Where(n => n.UserId == currentUser.UserId && n.ReadAt == null)
            .ExecuteUpdateAsync(s => s.SetProperty(n => n.ReadAt, DateTimeOffset.UtcNow));

        return new Success();
    }

    private static ApplicationError Unauthorized() => new(ErrorTypes.Unauthorized, "Unable to resolve current user.");
}
