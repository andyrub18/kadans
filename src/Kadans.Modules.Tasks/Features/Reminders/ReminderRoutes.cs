using Kadans.Modules.Tasks.Contracts;
using Kadans.SharedKernel.Http;
using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.AspNetCore.Mvc;
using OneOf.Types;

namespace Kadans.Modules.Tasks.Features.Reminders;

internal static class ReminderRoutes
{
    extension(IEndpointRouteBuilder app)
    {
        public void MapReminderRoutes()
        {
            var reminders = app.MapGroup("/reminders").WithTags("Reminders").RequireAuthorization();

            reminders
                .MapPost(
                    "/sync",
                    async Task<Results<Ok<ReminderWindowResponse>, ProblemHttpResult>> (
                        ReminderSyncRequest request,
                        ReminderWindows windows,
                        HttpContext context
                    ) => (await windows.Sync(request, context.RequestAborted)).ToHttp(context)
                )
                .WithName("RemindersSync")
                .WithSummary("Fetch the reminders this device rings itself")
                .WithDescription(
                    "The account's pending reminders of the next days (at most 7), soonest first, with the words to show. "
                        + "Recorded on the device: the server then pushes it only what it may not have. Empty for a phone "
                        + "without a subscription."
                )
                .Produces<ReminderWindowResponse>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status404NotFound);

            reminders
                .MapDelete(
                    "/sync/{installationId:guid}",
                    async Task<Results<NoContent, ProblemHttpResult>> (Guid installationId, ReminderWindows windows, HttpContext context) =>
                        (await windows.Stop(installationId, context.RequestAborted)).Match<Results<NoContent, ProblemHttpResult>>(
                            error => TypedResults.Problem(error.ToProblemDetails(context)),
                            _ => TypedResults.NoContent()
                        )
                )
                .WithName("RemindersStop")
                .WithSummary("Stop ringing reminders on this device")
                .WithDescription("The device no longer schedules reminders itself (its permission went): every reminder is pushed to it again.")
                .Produces(StatusCodes.Status204NoContent);

            reminders
                .MapGet(
                    "/{occurrenceId:guid}",
                    async Task<Ok<ReminderCheckResponse>> (
                        Guid occurrenceId,
                        ReminderWindows windows,
                        HttpContext context,
                        [FromQuery] DateTimeOffset? at = null
                    ) => TypedResults.Ok(await windows.Check(occurrenceId, at, context.RequestAborted))
                )
                .WithName("RemindersCheck")
                .WithSummary("Is this reminder still due?")
                .WithDescription(
                    "Asked as a device's own alarm rings, with the reminder's time (?at=): false once the occurrence is gone, done or "
                        + "cancelled, moved, or while a focus session for it runs. Its reminder and start times as they are now."
                )
                .Produces<ReminderCheckResponse>(StatusCodes.Status200OK);
        }
    }
}
