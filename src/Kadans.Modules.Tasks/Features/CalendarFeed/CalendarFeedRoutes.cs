using Kadans.Modules.Tasks.Contracts;
using Kadans.SharedKernel.Http;
using Microsoft.AspNetCore.Http.HttpResults;

namespace Kadans.Modules.Tasks.Features.CalendarFeed;

internal static class CalendarFeedRoutes
{
    extension(IEndpointRouteBuilder app)
    {
        public void MapCalendarFeedRoutes()
        {
            // Settings' side, signed in. Not under the feed's own prefix: that one is logged and limited differently.
            var settings = app.MapGroup("/calendar-feed").WithTags("Calendar feed").RequireAuthorization();

            settings
                .MapGet(
                    string.Empty,
                    async Task<Ok<CalendarFeedResponse>> (CalendarFeeds feeds, HttpContext context) =>
                        TypedResults.Ok(await feeds.State(context.RequestAborted))
                )
                .WithName("CalendarFeedGet")
                .WithSummary("Is the calendar feed on, and its link")
                .WithDescription(
                    "The account's calendar feed: off, or on with the link a calendar app subscribes to and the last time one read it. "
                        + "`available` is false for an account without the phones' subscription, which the feed is part of."
                )
                .Produces<CalendarFeedResponse>(StatusCodes.Status200OK);

            settings
                .MapPut(
                    string.Empty,
                    async Task<Results<Ok<CalendarFeedResponse>, ProblemHttpResult>> (CalendarFeeds feeds, HttpContext context) =>
                        (await feeds.TurnOn(context.RequestAborted)).ToHttp(context)
                )
                .WithName("CalendarFeedTurnOn")
                .WithSummary("Turn the calendar feed on")
                .WithDescription("Creates the account's link; already on, answers the link it has. 402 without the phones' subscription.")
                .Produces<CalendarFeedResponse>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status401Unauthorized)
                .ProducesProblem(StatusCodes.Status402PaymentRequired);

            settings
                .MapPost(
                    "/reset",
                    async Task<Results<Ok<CalendarFeedResponse>, ProblemHttpResult>> (CalendarFeeds feeds, HttpContext context) =>
                        (await feeds.Reset(context.RequestAborted)).ToHttp(context)
                )
                .WithName("CalendarFeedReset")
                .WithSummary("Replace the calendar feed's link")
                .WithDescription(
                    "A new link; the old one stops at once, and the calendars that followed it must be given the new one. "
                        + "402 without the phones' subscription."
                )
                .Produces<CalendarFeedResponse>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status401Unauthorized)
                .ProducesProblem(StatusCodes.Status402PaymentRequired);

            settings
                .MapDelete(
                    string.Empty,
                    async Task<Results<NoContent, ProblemHttpResult>> (CalendarFeeds feeds, HttpContext context) =>
                        (await feeds.TurnOff(context.RequestAborted)).Match<Results<NoContent, ProblemHttpResult>>(
                            error => TypedResults.Problem(error.ToProblemDetails(context)),
                            _ => TypedResults.NoContent()
                        )
                )
                .WithName("CalendarFeedTurnOff")
                .WithSummary("Turn the calendar feed off")
                .WithDescription("The link stops at once. Turned on again later, the feed has another link.")
                .Produces(StatusCodes.Status204NoContent)
                .ProducesProblem(StatusCodes.Status401Unauthorized);

            // The feed itself: no sign-in, the link is the credential (SecretLinks). HEAD too: some calendar apps
            // look before they read.
            app.MapMethods(
                    $"{SecretLinks.CalendarFeed}/{{secret}}/{CalendarFeedLinks.FileName}",
                    [HttpMethods.Get, HttpMethods.Head],
                    (string secret, CalendarFeedEndpoint endpoint, HttpContext context) => endpoint.Read(secret, context)
                )
                .AllowAnonymous()
                .WithTags("Calendar feed")
                .WithName("CalendarFeedRead")
                .WithSummary("Read a calendar feed")
                .WithDescription(
                    "The todos of the link's account as an iCalendar file: titles and times, pending and done, from 30 days back to "
                        + "90 days ahead. No sign-in: whoever holds the link reads it. 304 with If-None-Match when nothing changed; "
                        + "404 for a link that is unknown, reset, turned off, or whose account is closed. An account without the "
                        + "phones' subscription gets a calendar with nothing in it."
                )
                .Produces(StatusCodes.Status200OK, contentType: "text/calendar")
                .Produces(StatusCodes.Status304NotModified)
                .Produces(StatusCodes.Status404NotFound)
                .Produces(StatusCodes.Status429TooManyRequests);
        }
    }
}
