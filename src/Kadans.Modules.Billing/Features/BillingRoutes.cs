using Kadans.Modules.Billing.Contracts;
using Kadans.Modules.Billing.Google;
using Kadans.SharedKernel.Http;
using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.Extensions.Options;

namespace Kadans.Modules.Billing.Features;

internal static class BillingRoutes
{
    extension(IEndpointRouteBuilder routeBuilder)
    {
        public void MapBillingRoutes(BillingOptions options)
        {
            var billing = routeBuilder.MapGroup("/billing").WithTags("Billing").RequireAuthorization();

            billing.MapGet("/subscription", async Task<Results<Ok<SubscriptionStatusResponse>, ProblemHttpResult>> (Subscriptions service, HttpContext context, CancellationToken cancellationToken) =>
                    (await service.Status(cancellationToken)).ToHttp(context))
                .WithName("BillingSubscription")
                .WithSummary("This account's subscription")
                .WithDescription("Whether phones need a subscription (required), whether this account is paid up (hasAccess), and what a purchase must carry (accountHash, googleProductId).")
                .Produces<SubscriptionStatusResponse>();

            billing.MapPost("/google/purchases", async Task<Results<Ok<SubscriptionStatusResponse>, ProblemHttpResult>> (GooglePurchaseRequest request, Subscriptions service, HttpContext context, CancellationToken cancellationToken) =>
                    (await service.LinkGoogle(request, cancellationToken)).ToHttp(context))
                .WithName("BillingGooglePurchase")
                .WithSummary("Link a Google Play purchase")
                .WithDescription("After a purchase in the app, or to restore one: the server checks it with Google (it must be Kadans' subscription, made for this account), acknowledges it, and keeps it.")
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status409Conflict)
                .ProducesProblem(StatusCodes.Status503ServiceUnavailable);

            // Google Play's real-time developer notifications, pushed by Pub/Sub. Any 2xx acknowledges the message; an
            // error makes Pub/Sub retry, which is what a failed read from Google should do.
            routeBuilder.MapPost("/billing/google/notifications", async Task<IResult> (
                    HttpContext context,
                    PubSubPush push,
                    IPushAuthenticator authenticator,
                    Subscriptions service,
                    IOptions<BillingOptions> settings,
                    CancellationToken cancellationToken) =>
                {
                    if (!await authenticator.IsGooglePushAsync(context.Request.Headers.Authorization, cancellationToken))
                        return Results.Unauthorized();

                    var notification = DeveloperNotification.Decode(push.Message?.Data);
                    if (notification?.PackageName != settings.Value.Google.PackageName || notification.SubscriptionNotification?.PurchaseToken is not { } token)
                        return Results.NoContent(); // a test notification, another app's, or something Kadans does not sell

                    await service.SyncGoogle(token, notification.SubscriptionNotification.NotificationType == DeveloperNotification.Revoked, cancellationToken);
                    return Results.NoContent();
                })
                .AllowAnonymous()
                .WithTags("Billing")
                .WithName("BillingGoogleNotifications")
                .ExcludeFromDescription();

            if (options.FakeStore.Enabled)
            {
                billing.MapPost("/fake/purchases", async Task<Results<Ok<SubscriptionStatusResponse>, ProblemHttpResult>> (FakePurchaseRequest request, Subscriptions service, HttpContext context, CancellationToken cancellationToken) =>
                        (await service.Fake(request, cancellationToken)).ToHttp(context))
                    .WithName("BillingFakePurchase")
                    .WithSummary("Development: a subscription without a store")
                    .WithDescription("Only when Billing:FakeStore:Enabled (never in production): this account gets a subscription in that state, ending in that many days.");
            }
        }
    }
}
