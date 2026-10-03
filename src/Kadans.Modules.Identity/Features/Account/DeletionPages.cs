using Kadans.Modules.Identity.Features.Auth;
using Kadans.SharedKernel.Errors;
using Kadans.SharedKernel.Http;
using Kadans.SharedKernel.Localization;
using Microsoft.AspNetCore.Http.HttpResults;

namespace Kadans.Modules.Identity.Features.Account;

/// <summary>
/// The web route to deletion Google Play requires (for people without the app): <c>/account/delete</c> asks for the
/// address and sends a link; the link's page says what will happen, and its button closes the account. The forms
/// carry no session, so there is nothing to forge: the confirmation needs the emailed token.
/// </summary>
internal static class DeletionPages
{
    extension(IEndpointRouteBuilder routeBuilder)
    {
        public void MapAccountDeletionPages()
        {
            var pages = routeBuilder.MapGroup("/account/delete").AllowAnonymous().ExcludeFromDescription();

            pages.MapGet(string.Empty, (HttpContext context) =>
                TypedResults.Text(AuthPages.DeleteRequest(DeletionTexts.For(RequestLanguage.Of(context))), "text/html"));

            // The form is read by hand, not bound: a bound form would demand an antiforgery token, and there is no
            // session here to protect (a forged request only mails the owner a link).
            pages.MapPost(string.Empty, async (HttpContext context, AccountDeletions service, CancellationToken cancellationToken) =>
                {
                    var form = await context.Request.ReadFormAsync(cancellationToken);
                    await service.RequestByEmail(form["email"].ToString(), cancellationToken);
                    return TypedResults.Text(AuthPages.Message(DeletionTexts.For(RequestLanguage.Of(context)).PageSent), "text/html");
                })
                .RequireRateLimiting(RateLimitPolicies.Email);

            pages.MapGet("/confirm", async Task<ContentHttpResult> (string userId, string token, AccountDeletions service) =>
                {
                    var user = await service.FindByLinkAsync(userId, token);
                    if (user is null)
                        return TypedResults.Text(AuthPages.Message(DeletionTexts.For(null).InvalidLink), "text/html", statusCode: StatusCodes.Status400BadRequest);

                    var texts = DeletionTexts.For(user.PreferredLanguage);
                    var eraseOn = texts.Date(service.EraseAfterIfAskedNow(), IdentityEmails.ZoneOf(user));
                    return TypedResults.Text(AuthPages.DeleteConfirm(texts, user.UserName ?? user.Email ?? "", eraseOn, userId, token), "text/html");
                });

            pages.MapPost("/confirm", async Task<ContentHttpResult> (HttpContext context, AccountDeletions service, CancellationToken cancellationToken) =>
                {
                    var form = await context.Request.ReadFormAsync(cancellationToken);
                    var result = await service.ConfirmByLink(form["userId"].ToString(), form["token"].ToString(), cancellationToken);
                    return result.Match(
                        error => TypedResults.Text(
                            AuthPages.Message(ErrorTexts.Localize(error.ErrorType, error.ErrorMessage, RequestLanguage.Of(context))),
                            "text/html",
                            statusCode: error.ErrorType.HttpStatusCode),
                        done =>
                        {
                            var texts = DeletionTexts.For(done.User.PreferredLanguage);
                            return TypedResults.Text(
                                AuthPages.Message(string.Format(texts.ScheduledPage, "", texts.Date(done.EraseAfter, IdentityEmails.ZoneOf(done.User)))),
                                "text/html");
                        });
                })
                .RequireRateLimiting(RateLimitPolicies.Credentials);
        }
    }
}
