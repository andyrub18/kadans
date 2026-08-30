using Kadans.Modules.Identity.Contracts;
using Kadans.Modules.Identity.Features.Account;
using Kadans.Modules.Identity.Features.Users;
using Kadans.SharedKernel.Http;
using Microsoft.AspNetCore.Http.HttpResults;
using OneOf.Types;

namespace Kadans.Modules.Identity.Features.Auth;

internal static class AuthRoutes
{
    extension(IEndpointRouteBuilder routeBuilder)
    {
        public void MapAuthRoutes()
        {
            var auth = routeBuilder.MapGroup("/auth").WithTags("Auth").AllowAnonymous();

            auth.MapPost("/login", async Task<Results<Ok<LoginResponse>, ProblemHttpResult>> (LoginRequest request, Authentication service, HttpContext context) =>
                    (await service.Login(request)).ToHttp(context))
                .WithName("AuthLogin")
                .WithSummary("Sign in with username (or email) and password")
                .WithDescription("Returns a token pair, or an MFA challenge (`mfaRequired: true`) to complete at /auth/mfa/verify.")
                .ProducesProblem(StatusCodes.Status401Unauthorized)
                .ProducesProblem(StatusCodes.Status403Forbidden);

            auth.MapPost("/mfa/verify", async Task<Results<Ok<LoginResponse>, ProblemHttpResult>> (MfaVerifyRequest request, Authentication service, HttpContext context) =>
                    (await service.VerifyMfa(request)).ToHttp(context))
                .WithName("AuthMfaVerify")
                .WithSummary("Complete a login with a TOTP or recovery code")
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status401Unauthorized);

            auth.MapPost("/external", async Task<Results<Ok<LoginResponse>, ProblemHttpResult>> (ExternalLoginRequest request, ExternalAuthentication service, HttpContext context, CancellationToken cancellationToken) =>
                    (await service.SignIn(request, cancellationToken)).ToHttp(context))
                .WithName("AuthExternal")
                .WithSummary("Sign in with a Google or Apple ID token")
                .WithDescription("The client obtains the ID token natively; the API verifies it against the provider's keys and links or creates the account.")
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status401Unauthorized);

            auth.MapPost("/refresh", async Task<Results<Ok<LoginResponse>, ProblemHttpResult>> (RefreshTokenRequest request, Authentication service, HttpContext context) =>
                    (await service.RefreshToken(request)).ToHttp(context))
                .WithName("AuthRefresh")
                .WithSummary("Rotate a refresh token")
                .WithDescription("Reusing an already-rotated token revokes the whole session family.")
                .ProducesProblem(StatusCodes.Status401Unauthorized);

            auth.MapPost("/revoke", async Task<Ok<Success>> (RevokeRefreshTokenRequest request, Authentication service) =>
                {
                    await service.RevokeRefreshToken(request.RefreshToken);
                    return TypedResults.Ok(new Success());
                })
                .WithName("AuthRevoke")
                .WithSummary("Log out (revoke the session this refresh token belongs to)");

            auth.MapPost("/register", async Task<Results<Ok<UserResponse>, ProblemHttpResult>> (RegisterUserRequest request, UserManagement service, HttpContext context) =>
                    (await service.RegisterUser(request)).ToHttp(context))
                .WithName("AuthRegister")
                .WithSummary("Register")
                .WithDescription("Creates the account and emails a confirmation link.")
                .ProducesProblem(StatusCodes.Status400BadRequest);

            auth.MapPost("/confirm-email", async Task<Results<Ok<Success>, ProblemHttpResult>> (ConfirmEmailRequest request, AccountSecurity service, HttpContext context) =>
                    (await service.ConfirmEmail(request)).ToHttp(context))
                .WithName("AuthConfirmEmail")
                .WithSummary("Confirm an email address")
                .ProducesProblem(StatusCodes.Status400BadRequest);

            // The link in the email is a GET so it also works from a plain browser click.
            auth.MapGet("/confirm-email", async Task<Results<ContentHttpResult, ProblemHttpResult>> (string userId, string token, AccountSecurity service, HttpContext context) =>
                {
                    var result = await service.ConfirmEmail(new ConfirmEmailRequest(userId, token));
                    return result.Match<Results<ContentHttpResult, ProblemHttpResult>>(
                        error => TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                        _ => TypedResults.Text("Your email is confirmed. You can go back to the app."));
                })
                .WithName("AuthConfirmEmailLink")
                .WithSummary("Confirm an email address (link target)")
                .ExcludeFromDescription();

            auth.MapPost("/resend-confirmation", async Task<Ok<Success>> (ResendConfirmationRequest request, AccountSecurity service, CancellationToken cancellationToken) =>
                    TypedResults.Ok(await service.ResendConfirmation(request, cancellationToken)))
                .WithName("AuthResendConfirmation")
                .WithSummary("Send the confirmation email again");

            auth.MapPost("/forgot-password", async Task<Ok<Success>> (ForgotPasswordRequest request, AccountSecurity service, CancellationToken cancellationToken) =>
                    TypedResults.Ok(await service.ForgotPassword(request, cancellationToken)))
                .WithName("AuthForgotPassword")
                .WithSummary("Email a password reset link")
                .WithDescription("Always returns 200 so that email addresses cannot be enumerated.");

            auth.MapPost("/reset-password", async Task<Results<Ok<Success>, ProblemHttpResult>> (ResetPasswordRequest request, AccountSecurity service, HttpContext context) =>
                    (await service.ResetPassword(request)).ToHttp(context))
                .WithName("AuthResetPassword")
                .WithSummary("Set a new password with a reset token")
                .WithDescription("All existing sessions are revoked.")
                .ProducesProblem(StatusCodes.Status400BadRequest);
        }
    }
}
