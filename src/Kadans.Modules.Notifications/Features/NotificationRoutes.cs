using Kadans.Modules.Notifications.Contracts;
using Kadans.SharedKernel.Http;
using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.AspNetCore.Mvc;
using OneOf.Types;

namespace Kadans.Modules.Notifications.Features;

internal static class NotificationRoutes
{
    extension(IEndpointRouteBuilder app)
    {
        public void MapNotificationRoutes()
        {
            var group = app.MapGroup("/notifications").WithTags("Notifications").RequireAuthorization();

            group.MapGet(string.Empty, async Task<Results<Ok<List<NotificationResponse>>, ProblemHttpResult>> (NotificationQueries service, HttpContext context, [FromQuery] bool unreadOnly = false, [FromQuery] int page = 1, [FromQuery] int pageSize = 20) =>
                    (await service.List(unreadOnly, page, pageSize)).ToHttp(context))
                .WithName("NotificationsList")
                .WithSummary("List notifications, newest first");

            group.MapGet("/unread-count", async Task<Results<Ok<UnreadCountResponse>, ProblemHttpResult>> (NotificationQueries service, HttpContext context) =>
                    (await service.UnreadCount()).ToHttp(context))
                .WithName("NotificationsUnreadCount")
                .WithSummary("Number of unread notifications");

            group.MapPut("/{id:guid}/read", async Task<Results<Ok<Success>, ProblemHttpResult>> (Guid id, NotificationQueries service, HttpContext context) =>
                    (await service.MarkRead(id)).ToHttp(context))
                .WithName("NotificationsMarkRead")
                .WithSummary("Mark one notification read")
                .ProducesProblem(StatusCodes.Status404NotFound);

            group.MapPut("/read-all", async Task<Results<Ok<Success>, ProblemHttpResult>> (NotificationQueries service, HttpContext context) =>
                    (await service.MarkAllRead()).ToHttp(context))
                .WithName("NotificationsMarkAllRead")
                .WithSummary("Mark every notification read");
        }
    }
}
