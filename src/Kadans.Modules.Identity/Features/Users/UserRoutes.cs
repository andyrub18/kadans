using Kadans.Modules.Identity.Contracts;
using Kadans.Modules.Identity.Features.Account;
using Kadans.Modules.Identity.Features.Devices;
using Kadans.SharedKernel.Http;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http.HttpResults;
using OneOf.Types;

namespace Kadans.Modules.Identity.Features.Users;

internal static class UserRoutes
{
    extension(IEndpointRouteBuilder routeBuilder)
    {
        public void MapUserRoutes()
        {
            var me = routeBuilder.MapGroup("/users/me").WithTags("Account").RequireAuthorization();

            me.MapGet(string.Empty, async Task<Results<Ok<UserResponse>, ProblemHttpResult>> (UserManagement service, HttpContext context) =>
                    (await service.GetCurrentUser()).ToHttp(context))
                .WithName("UsersMe")
                .WithSummary("Current user profile");

            me.MapPut(string.Empty, async Task<Results<Ok<UserResponse>, ProblemHttpResult>> (UpdateSelfUserRequest request, UserManagement service, HttpContext context) =>
                    (await service.UpdateCurrentUser(request)).ToHttp(context))
                .WithName("UsersUpdateSelf")
                .WithSummary("Update own profile")
                .WithDescription("Username, display name and time zone. Email and password have dedicated flows.")
                .ProducesProblem(StatusCodes.Status400BadRequest);

            me.MapPut("/deactivate", async Task<Results<Ok<UserResponse>, ProblemHttpResult>> (UserManagement service, HttpContext context) =>
                    (await service.DeactivateCurrentUser()).ToHttp(context))
                .WithName("UsersDeactivateSelf")
                .WithSummary("Deactivate own account");

            me.MapPut("/password", async Task<Results<Ok<Success>, ProblemHttpResult>> (ChangePasswordRequest request, AccountSecurity service, HttpContext context) =>
                    (await service.ChangePassword(request)).ToHttp(context))
                .WithName("UsersChangePassword")
                .WithSummary("Change password")
                .WithDescription("Requires the current password. All sessions are revoked afterwards.")
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status401Unauthorized);

            me.MapPost("/sessions/revoke-all", async Task<Results<Ok<Success>, ProblemHttpResult>> (AccountSecurity service, HttpContext context) =>
                    (await service.RevokeAllSessions()).ToHttp(context))
                .WithName("UsersRevokeAllSessions")
                .WithSummary("Log out everywhere");

            me.MapPost("/email", async Task<Results<Ok<Success>, ProblemHttpResult>> (ChangeEmailRequest request, AccountSecurity service, HttpContext context, CancellationToken cancellationToken) =>
                    (await service.RequestEmailChange(request, cancellationToken)).ToHttp(context))
                .WithName("UsersRequestEmailChange")
                .WithSummary("Start an email change")
                .WithDescription("Sends a confirmation link to the new address; the change applies once confirmed.")
                .ProducesProblem(StatusCodes.Status400BadRequest);

            me.MapPost("/email/confirm", async Task<Results<Ok<Success>, ProblemHttpResult>> (ConfirmEmailChangeRequest request, AccountSecurity service, HttpContext context) =>
                    (await service.ConfirmEmailChange(request)).ToHttp(context))
                .WithName("UsersConfirmEmailChange")
                .WithSummary("Confirm a new email address")
                .ProducesProblem(StatusCodes.Status400BadRequest);

            var mfa = me.MapGroup("/mfa");

            mfa.MapPost("/enroll", async Task<Results<Ok<MfaEnrollResponse>, ProblemHttpResult>> (AccountSecurity service, HttpContext context) =>
                    (await service.MfaEnroll()).ToHttp(context))
                .WithName("UsersMfaEnroll")
                .WithSummary("Start TOTP enrolment")
                .WithDescription("Returns the shared key and an otpauth:// URI to show as a QR code. Nothing is enforced until /enable.")
                .ProducesProblem(StatusCodes.Status400BadRequest);

            mfa.MapPost("/enable", async Task<Results<Ok<RecoveryCodesResponse>, ProblemHttpResult>> (MfaCodeRequest request, AccountSecurity service, HttpContext context) =>
                    (await service.MfaEnable(request)).ToHttp(context))
                .WithName("UsersMfaEnable")
                .WithSummary("Enable TOTP with a first code; returns recovery codes")
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status401Unauthorized);

            mfa.MapPost("/disable", async Task<Results<Ok<Success>, ProblemHttpResult>> (MfaCodeRequest request, AccountSecurity service, HttpContext context) =>
                    (await service.MfaDisable(request)).ToHttp(context))
                .WithName("UsersMfaDisable")
                .WithSummary("Disable TOTP (code or recovery code required)")
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status401Unauthorized);

            mfa.MapPost("/recovery-codes", async Task<Results<Ok<RecoveryCodesResponse>, ProblemHttpResult>> (MfaCodeRequest request, AccountSecurity service, HttpContext context) =>
                    (await service.MfaRegenerateRecoveryCodes(request)).ToHttp(context))
                .WithName("UsersMfaRecoveryCodes")
                .WithSummary("Generate a new set of recovery codes")
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status401Unauthorized);

            var devices = me.MapGroup("/devices").WithTags("Devices");

            devices.MapGet(string.Empty, async Task<Results<Ok<List<DeviceResponse>>, ProblemHttpResult>> (DeviceService service, HttpContext context) =>
                    (await service.List()).ToHttp(context))
                .WithName("DevicesList")
                .WithSummary("List registered devices");

            devices.MapPut("/{installationId:guid}", async Task<Results<Ok<DeviceResponse>, ProblemHttpResult>> (Guid installationId, RegisterDeviceRequest request, DeviceService service, HttpContext context) =>
                    (await service.Register(installationId, request)).ToHttp(context))
                .WithName("DevicesRegister")
                .WithSummary("Register or update a device")
                .WithDescription("Idempotent on the client-generated installation id; call on every app start to refresh the push token.")
                .ProducesProblem(StatusCodes.Status400BadRequest);

            devices.MapDelete("/{installationId:guid}", async Task<Results<Ok<Success>, ProblemHttpResult>> (Guid installationId, DeviceService service, HttpContext context) =>
                    (await service.Remove(installationId)).ToHttp(context))
                .WithName("DevicesRemove")
                .WithSummary("Unregister a device")
                .ProducesProblem(StatusCodes.Status404NotFound);

            var admin = routeBuilder.MapGroup("/users").WithTags("Users (admin)").RequireAuthorization(new AuthorizeAttribute { Roles = "Admin" });

            admin.MapPost(string.Empty, async Task<Results<Ok<UserResponse>, ProblemHttpResult>> (CreateUserRequest request, UserManagement service, HttpContext context) =>
                    (await service.CreateUser(request)).ToHttp(context))
                .WithName("UsersCreate")
                .WithSummary("Create a user")
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status403Forbidden);

            admin.MapPut("/{userId}", async Task<Results<Ok<UserResponse>, ProblemHttpResult>> (string userId, UpdateUserRequest request, UserManagement service, HttpContext context) =>
                    (await service.UpdateUser(userId, request)).ToHttp(context))
                .WithName("UsersUpdate")
                .WithSummary("Update a user")
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status403Forbidden)
                .ProducesProblem(StatusCodes.Status404NotFound);

            admin.MapPut("/{userId}/deactivate", async Task<Results<Ok<UserResponse>, ProblemHttpResult>> (string userId, UserManagement service, HttpContext context) =>
                    (await service.DeactivateUser(userId)).ToHttp(context))
                .WithName("UsersDeactivate")
                .WithSummary("Deactivate a user")
                .ProducesProblem(StatusCodes.Status403Forbidden)
                .ProducesProblem(StatusCodes.Status404NotFound);
        }
    }
}
