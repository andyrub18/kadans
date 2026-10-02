using Kadans.Modules.Identity.Contracts;
using Kadans.Modules.Identity.Domain;
using Kadans.Modules.Identity.Features.Auth;
using Kadans.Modules.Identity.Features.Users;
using Kadans.Modules.Identity.Security;
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
    Sessions sessions,
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
        if (await CheckCurrentPasswordAsync(user, request.CurrentPassword) is { } wrongPassword)
            return wrongPassword;

        var result = await userManager.ChangePasswordAsync(user, request.CurrentPassword, request.NewPassword);
        if (!result.Succeeded)
        {
            if (result.Errors.Any(e => e.Code == nameof(IdentityErrorDescriber.PasswordMismatch)))
                return new ApplicationError(ErrorTypes.InvalidCredentials, "The current password is not correct.");

            return result.ToValidationError("Validation failed for changing password.");
        }

        await sessions.EndAllAsync(user.Id, "password changed");
        logger.LogInformation("User {UserId} changed their password", user.Id);
        return new Success();
    }

    /// <summary>Always succeeds from the caller's point of view so that emails cannot be enumerated.</summary>
    public async Task<Success> ForgotPassword(ForgotPasswordRequest request, CancellationToken cancellationToken)
    {
        // A lock after wrong passwords does not withhold the reset: the mailbox is how the owner gets back in.
        var user = await userManager.FindByEmailAsync(request.Email);
        if (user is not null && !AccountLock.IsDeactivated(user))
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

        // The link proved the mailbox: a lock after wrong passwords is lifted along with the old password.
        await userManager.ResetAccessFailedCountAsync(user);
        if (!AccountLock.IsDeactivated(user))
            await userManager.SetLockoutEndDateAsync(user, null);

        await sessions.EndAllAsync(user.Id, "password reset");
        logger.LogInformation("User {UserId} reset their password", user.Id);
        return new Success();
    }

    public async Task<OneOf<ApplicationError, Success>> RevokeAllSessions()
    {
        var user = await CurrentUserAsync();
        if (user is null)
            return Unauthorized();

        await sessions.EndAllAsync(user.Id, "revoked by user");
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

        // The address is where reset links go: changing it takes the password too, so a session left open on
        // someone else's screen is not enough to take the account. Accounts that only sign in with Google have none.
        if (await userManager.HasPasswordAsync(user))
        {
            if (string.IsNullOrEmpty(request.CurrentPassword))
                return new ValidationError(ErrorTypes.ValidationError, "Validation failed for changing email.", [("CurrentPasswordRequired", "Enter your current password.")]);
            if (await CheckCurrentPasswordAsync(user, request.CurrentPassword) is { } wrongPassword)
                return wrongPassword;
        }

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

        return await ApplyEmailChangeAsync(user, request.NewEmail, request.Token);
    }

    /// <summary>
    /// The emailed link, opened in a browser with no session. The token is bound to this user and
    /// this address and was only ever sent there, so holding it is the proof — same model as confirm-email.
    /// </summary>
    public async Task<OneOf<ApplicationError, Success>> ConfirmEmailChangeByLink(string userId, string newEmail, string token)
    {
        var user = await userManager.FindByIdAsync(userId);
        if (user is null)
            return new ApplicationError(ErrorTypes.InvalidToken, "The confirmation link is invalid or expired.");

        return await ApplyEmailChangeAsync(user, newEmail, token);
    }

    private async Task<OneOf<ApplicationError, Success>> ApplyEmailChangeAsync(ApplicationUser user, string newEmail, string encodedToken)
    {
        var token = IdentityEmails.Decode(encodedToken);
        if (token is null)
            return new ApplicationError(ErrorTypes.InvalidToken, "The confirmation link is invalid or expired.");

        newEmail = newEmail.Trim();
        var oldEmail = user.Email;
        if (string.Equals(oldEmail, newEmail, StringComparison.OrdinalIgnoreCase))
            return new Success(); // the link was opened twice (or a mail scanner got there first)

        var result = await userManager.ChangeEmailAsync(user, newEmail, token);
        if (!result.Succeeded)
        {
            if (result.Errors.Any(e => e.Code == nameof(IdentityErrorDescriber.InvalidToken)))
                return new ApplicationError(ErrorTypes.InvalidToken, "The confirmation link is invalid or expired.");

            return result.ToValidationError("Validation failed for changing email.");
        }

        logger.LogInformation("User {UserId} changed their email", user.Id);

        // A username that was the old address (accounts created by Google sign-in) follows it: an "@" in a username
        // only ever names the account's own address.
        if (oldEmail is not null && string.Equals(user.UserName, oldEmail, StringComparison.OrdinalIgnoreCase)
            && await userManager.FindByNameAsync(newEmail) is null)
        {
            var renamed = await userManager.SetUserNameAsync(user, newEmail);
            if (!renamed.Succeeded)
                logger.LogWarning("User {UserId} keeps the old address as username: {Errors}", user.Id, string.Join("; ", renamed.Errors.Select(e => e.Code)));
        }

        if (!string.IsNullOrWhiteSpace(oldEmail))
        {
            try
            {
                await emails.SendEmailChangedNoticeAsync(user, oldEmail, newEmail);
            }
            catch (Exception ex)
            {
                logger.LogWarning(ex, "Could not notify the previous address of user {UserId}", user.Id);
            }
        }

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

        if (await CheckCodeAsync(user, () => authentication.VerifyAuthenticatorOrRecoveryCodeAsync(user, request.Code)) is { } wrongCode)
            return wrongCode;

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

        if (await CheckCodeAsync(user, () => VerifyAuthenticatorAsync(user, request.Code)) is { } wrongCode)
            return wrongCode;

        var codes = await userManager.GenerateNewTwoFactorRecoveryCodesAsync(user, RecoveryCodeCount);
        return new RecoveryCodesResponse([.. codes ?? []]);
    }

    // ---------- helpers ----------

    /// <summary>
    /// The password, asked again before a sensitive change. Wrong guesses count toward the lockout as sign-in
    /// attempts do, so a session left open cannot be used to try passwords at leisure.
    /// </summary>
    private async Task<ApplicationError?> CheckCurrentPasswordAsync(ApplicationUser user, string password)
    {
        if (await userManager.IsLockedOutAsync(user))
            return AccountLock.Refusal(user);

        if (await userManager.CheckPasswordAsync(user, password))
        {
            await userManager.ResetAccessFailedCountAsync(user);
            return null;
        }

        await userManager.AccessFailedAsync(user);
        return new ApplicationError(ErrorTypes.InvalidCredentials, "The current password is not correct.");
    }

    /// <summary>A 2FA code for turning 2FA off or replacing the recovery codes: wrong ones count like at sign-in.</summary>
    private async Task<ApplicationError?> CheckCodeAsync(ApplicationUser user, Func<Task<bool>> verify)
    {
        if (await userManager.IsLockedOutAsync(user))
            return AccountLock.Refusal(user);

        if (await verify())
        {
            await userManager.ResetAccessFailedCountAsync(user);
            return null;
        }

        await userManager.AccessFailedAsync(user);
        return new ApplicationError(ErrorTypes.MfaCodeInvalid, "The verification code is not valid.");
    }

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
