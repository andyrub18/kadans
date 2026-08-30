using Kadans.Modules.Identity.Contracts;
using Kadans.Modules.Identity.Domain;
using Kadans.Modules.Identity.Features.Auth;
using Kadans.Modules.Identity.Features.Users;
using Kadans.SharedKernel.Errors;
using Kadans.SharedKernel.Security;
using Microsoft.AspNetCore.Identity;
using OneOf;
using OneOf.Types;

namespace Kadans.Modules.Identity.Features.Account;

/// <summary>Self-service credential flows: passwords, email verification/change, TOTP MFA.</summary>
internal sealed class AccountSecurity(
    UserManager<ApplicationUser> userManager,
    ICurrentUserService currentUser,
    IdentityEmails emails,
    Authentication authentication,
    ILogger<AccountSecurity> logger
)
{
    private const int RecoveryCodeCount = 8;
    private const string Issuer = "Kadans";

    // ---------- passwords ----------

    public async Task<OneOf<ApplicationError, Success>> ChangePassword(ChangePasswordRequest request)
    {
        var user = await CurrentUserAsync();
        if (user is null)
            return Unauthorized();

        var result = await userManager.ChangePasswordAsync(user, request.CurrentPassword, request.NewPassword);
        if (!result.Succeeded)
        {
            if (result.Errors.Any(e => e.Code == nameof(IdentityErrorDescriber.PasswordMismatch)))
                return new ApplicationError(ErrorTypes.InvalidCredentials, "The current password is not correct.");

            return result.ToValidationError("Validation failed for changing password.");
        }

        await authentication.RevokeAllSessionsAsync(user.Id, "password changed");
        logger.LogInformation("User {UserId} changed their password", user.Id);
        return new Success();
    }

    /// <summary>Always succeeds from the caller's point of view so that emails cannot be enumerated.</summary>
    public async Task<Success> ForgotPassword(ForgotPasswordRequest request, CancellationToken cancellationToken)
    {
        var user = await userManager.FindByEmailAsync(request.Email);
        if (user is not null && !await userManager.IsLockedOutAsync(user))
            await emails.SendPasswordResetAsync(user, cancellationToken);
        else
            logger.LogInformation("Password reset requested for unknown or inactive email");

        return new Success();
    }

    public async Task<OneOf<ApplicationError, Success>> ResetPassword(ResetPasswordRequest request)
    {
        var user = await userManager.FindByEmailAsync(request.Email);
        var token = IdentityEmails.Decode(request.Token);
        if (user is null || token is null)
            return new ApplicationError(ErrorTypes.InvalidToken, "The reset link is invalid or expired.");

        var result = await userManager.ResetPasswordAsync(user, token, request.NewPassword);
        if (!result.Succeeded)
        {
            if (result.Errors.Any(e => e.Code == nameof(IdentityErrorDescriber.InvalidToken)))
                return new ApplicationError(ErrorTypes.InvalidToken, "The reset link is invalid or expired.");

            return result.ToValidationError("Validation failed for resetting password.");
        }

        await authentication.RevokeAllSessionsAsync(user.Id, "password reset");
        logger.LogInformation("User {UserId} reset their password", user.Id);
        return new Success();
    }

    public async Task<OneOf<ApplicationError, Success>> RevokeAllSessions()
    {
        var user = await CurrentUserAsync();
        if (user is null)
            return Unauthorized();

        await authentication.RevokeAllSessionsAsync(user.Id, "revoked by user");
        return new Success();
    }

    // ---------- email ----------

    public async Task<OneOf<ApplicationError, Success>> ConfirmEmail(ConfirmEmailRequest request)
    {
        var user = await userManager.FindByIdAsync(request.UserId);
        var token = IdentityEmails.Decode(request.Token);
        if (user is null || token is null)
            return new ApplicationError(ErrorTypes.InvalidToken, "The confirmation link is invalid or expired.");

        if (user.EmailConfirmed)
            return new Success();

        var result = await userManager.ConfirmEmailAsync(user, token);
        if (!result.Succeeded)
            return new ApplicationError(ErrorTypes.InvalidToken, "The confirmation link is invalid or expired.");

        logger.LogInformation("User {UserId} confirmed their email", user.Id);
        return new Success();
    }

    public async Task<Success> ResendConfirmation(ResendConfirmationRequest request, CancellationToken cancellationToken)
    {
        var user = await userManager.FindByEmailAsync(request.Email);
        if (user is { EmailConfirmed: false })
            await emails.SendConfirmationAsync(user, cancellationToken);

        return new Success();
    }

    public async Task<OneOf<ApplicationError, Success>> RequestEmailChange(ChangeEmailRequest request, CancellationToken cancellationToken)
    {
        var user = await CurrentUserAsync();
        if (user is null)
            return Unauthorized();

        var newEmail = request.NewEmail.Trim();
        if (string.Equals(newEmail, user.Email, StringComparison.OrdinalIgnoreCase))
            return new Success();

        if (await userManager.FindByEmailAsync(newEmail) is not null)
            return new ApplicationError(ErrorTypes.EmailAlreadyInUse, "This email address is already in use.");

        await emails.SendEmailChangeAsync(user, newEmail, cancellationToken);
        return new Success();
    }

    public async Task<OneOf<ApplicationError, Success>> ConfirmEmailChange(ConfirmEmailChangeRequest request)
    {
        var user = await CurrentUserAsync();
        if (user is null)
            return Unauthorized();

        var token = IdentityEmails.Decode(request.Token);
        if (token is null)
            return new ApplicationError(ErrorTypes.InvalidToken, "The confirmation link is invalid or expired.");

        var result = await userManager.ChangeEmailAsync(user, request.NewEmail.Trim(), token);
        if (!result.Succeeded)
        {
            if (result.Errors.Any(e => e.Code == nameof(IdentityErrorDescriber.InvalidToken)))
                return new ApplicationError(ErrorTypes.InvalidToken, "The confirmation link is invalid or expired.");

            return result.ToValidationError("Validation failed for changing email.");
        }

        logger.LogInformation("User {UserId} changed their email", user.Id);
        return new Success();
    }

    // ---------- TOTP ----------

    public async Task<OneOf<ApplicationError, MfaEnrollResponse>> MfaEnroll()
    {
        var user = await CurrentUserAsync();
        if (user is null)
            return Unauthorized();

        if (user.TwoFactorEnabled)
            return new ApplicationError(ErrorTypes.MfaAlreadyEnabled, "Disable two-factor authentication before enrolling again.");

        await userManager.ResetAuthenticatorKeyAsync(user);
        var key = await userManager.GetAuthenticatorKeyAsync(user)
            ?? throw new InvalidOperationException("Authenticator key was not generated.");

        var account = Uri.EscapeDataString(user.Email ?? user.UserName ?? user.Id);
        var uri = $"otpauth://totp/{Issuer}:{account}?secret={key}&issuer={Issuer}&digits=6";

        return new MfaEnrollResponse(FormatKey(key), uri);
    }

    public async Task<OneOf<ApplicationError, RecoveryCodesResponse>> MfaEnable(MfaCodeRequest request)
    {
        var user = await CurrentUserAsync();
        if (user is null)
            return Unauthorized();

        if (user.TwoFactorEnabled)
            return new ApplicationError(ErrorTypes.MfaAlreadyEnabled, "Two-factor authentication is already enabled.");

        if (!await VerifyAuthenticatorAsync(user, request.Code))
            return new ApplicationError(ErrorTypes.MfaCodeInvalid, "The verification code is not valid.");

        await userManager.SetTwoFactorEnabledAsync(user, true);
        var codes = await userManager.GenerateNewTwoFactorRecoveryCodesAsync(user, RecoveryCodeCount);

        logger.LogInformation("User {UserId} enabled two-factor authentication", user.Id);
        return new RecoveryCodesResponse([.. codes ?? []]);
    }

    public async Task<OneOf<ApplicationError, Success>> MfaDisable(MfaCodeRequest request)
    {
        var user = await CurrentUserAsync();
        if (user is null)
            return Unauthorized();

        if (!user.TwoFactorEnabled)
            return new ApplicationError(ErrorTypes.MfaNotEnabled, "Two-factor authentication is not enabled.");

        if (!await authentication.VerifyAuthenticatorOrRecoveryCodeAsync(user, request.Code))
            return new ApplicationError(ErrorTypes.MfaCodeInvalid, "The verification code is not valid.");

        await userManager.SetTwoFactorEnabledAsync(user, false);
        await userManager.ResetAuthenticatorKeyAsync(user);

        logger.LogInformation("User {UserId} disabled two-factor authentication", user.Id);
        return new Success();
    }

    public async Task<OneOf<ApplicationError, RecoveryCodesResponse>> MfaRegenerateRecoveryCodes(MfaCodeRequest request)
    {
        var user = await CurrentUserAsync();
        if (user is null)
            return Unauthorized();

        if (!user.TwoFactorEnabled)
            return new ApplicationError(ErrorTypes.MfaNotEnabled, "Two-factor authentication is not enabled.");

        if (!await VerifyAuthenticatorAsync(user, request.Code))
            return new ApplicationError(ErrorTypes.MfaCodeInvalid, "The verification code is not valid.");

        var codes = await userManager.GenerateNewTwoFactorRecoveryCodesAsync(user, RecoveryCodeCount);
        return new RecoveryCodesResponse([.. codes ?? []]);
    }

    // ---------- helpers ----------

    private Task<bool> VerifyAuthenticatorAsync(ApplicationUser user, string code) =>
        userManager.VerifyTwoFactorTokenAsync(
            user,
            userManager.Options.Tokens.AuthenticatorTokenProvider,
            code.Replace(" ", string.Empty)
        );

    private async Task<ApplicationUser?> CurrentUserAsync() =>
        currentUser.UserId is null ? null : await userManager.FindByIdAsync(currentUser.UserId);

    private static ApplicationError Unauthorized() =>
        new(ErrorTypes.Unauthorized, "Unable to resolve current user.");

    /// <summary>Groups the base32 key in fours for manual entry, e.g. <c>abcd efgh ...</c>.</summary>
    private static string FormatKey(string key) =>
        string.Join(' ', Enumerable.Range(0, (key.Length + 3) / 4).Select(i => key.Substring(i * 4, Math.Min(4, key.Length - i * 4)))).ToLowerInvariant();
}
