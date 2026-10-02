using Kadans.Modules.Identity.Contracts;
using Kadans.Modules.Identity.Domain;
using Kadans.Modules.Identity.Persistence;
using Kadans.Modules.Identity.Security;
using Kadans.SharedKernel.Errors;
using Kadans.SharedKernel.Localization;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using OneOf;

namespace Kadans.Modules.Identity.Features.Auth;

/// <summary>Sign-in with a natively obtained Google/Apple ID token: link or create the local account.</summary>
internal sealed class ExternalAuthentication(
    ExternalIdTokenValidator validator,
    GoogleCodeExchange googleCodeExchange,
    IOptions<ExternalAuthOptions> options,
    UserManager<ApplicationUser> userManager,
    Authentication authentication,
    IdentityModuleDbContext dbContext,
    ILogger<ExternalAuthentication> logger
)
{
    public async Task<OneOf<ApplicationError, LoginResponse>> SignIn(
        ExternalLoginRequest request,
        CancellationToken cancellationToken
    )
    {
        var validation = await validator.ValidateAsync(request.Provider, request.IdToken, cancellationToken);
        if (validation.IsT0)
            return validation.AsT0;

        return await SignInAsync(validation.AsT1, cancellationToken, new NewAccountProfile(request.TimeZone, request.Language));
    }

    /// <summary>
    /// After the provider's ID token checked out: find, link or create the account, then hand out tokens.
    /// <paramref name="newAccount"/> (the device's time zone and language) is used only when the account is created.
    /// </summary>
    internal async Task<OneOf<ApplicationError, LoginResponse>> SignInAsync(
        ExternalIdentity external,
        CancellationToken cancellationToken,
        NewAccountProfile? newAccount = null
    )
    {
        var user = await userManager.FindByLoginAsync(external.Provider, external.Subject);
        if (user is null)
        {
            var linkResult = await LinkOrCreateAsync(external, newAccount, cancellationToken);
            if (linkResult.IsT0)
                return linkResult.AsT0;
            user = linkResult.AsT1;
        }

        if (await userManager.IsLockedOutAsync(user))
            return new ApplicationError(ErrorTypes.UserInactive, "User is deactivated");

        return await authentication.IssueTokensOrChallengeAsync(user);
    }

    /// <summary>Desktop: trade the loopback authorization code for an ID token, then sign in as usual.</summary>
    public async Task<OneOf<ApplicationError, LoginResponse>> SignInWithGoogleCode(
        GoogleCodeLoginRequest request,
        CancellationToken cancellationToken
    )
    {
        var exchanged = await googleCodeExchange.ExchangeAsync(request.Code, request.CodeVerifier, request.RedirectUri, cancellationToken);
        if (exchanged.IsT0)
            return exchanged.AsT0;

        return await SignIn(
            new ExternalLoginRequest(ExternalIdTokenValidator.Google, exchanged.AsT1, request.TimeZone, request.Language),
            cancellationToken
        );
    }

    /// <summary>What the clients may offer. A platform's id is only published when its flow can complete.</summary>
    public AuthProvidersResponse Providers()
    {
        var google = options.Value.Google;
        var desktopClientId = google.Desktop.IsConfigured ? google.Desktop.ClientId!.Trim() : null;
        var webClientId = string.IsNullOrWhiteSpace(google.WebClientId) ? null : google.WebClientId.Trim();
        return new AuthProvidersResponse(
            desktopClientId is null && webClientId is null ? null : new GoogleProviderResponse(desktopClientId, webClientId)
        );
    }

    private async Task<OneOf<ApplicationError, ApplicationUser>> LinkOrCreateAsync(
        ExternalIdentity external,
        NewAccountProfile? newAccount,
        CancellationToken cancellationToken
    )
    {
        // A verified address is what proves who an account belongs to. Without one there is nothing safe to link
        // to, and an account created on an unproven address would squat it. (Already-linked logins never get here.)
        if (!external.EmailVerified || string.IsNullOrWhiteSpace(external.Email))
            return new ApplicationError(ErrorTypes.ExternalLoginFailed, "This sign-in did not come with a verified email address.");
        var email = external.Email.Trim();

        // Claiming, creating and linking land together or not at all.
        await using var transaction = await dbContext.Database.BeginTransactionAsync(cancellationToken);

        var user = await userManager.FindByEmailAsync(email);
        if (user is { EmailConfirmed: false } && !await ClaimUnconfirmedAsync(user, external, cancellationToken))
            return new ApplicationError(ErrorTypes.ExternalLoginFailed, "Could not link this login to the account.");

        if (user is null)
        {
            user = new ApplicationUser
            {
                UserName = await PickUsernameAsync(external, email),
                Email = email,
                EmailConfirmed = true,
                DisplayName = external.DisplayName,
                LockoutEnabled = true,
                // Like registration: the device's zone and language, so reminders and mails are right from the start.
                TimeZoneId = newAccount?.TimeZone is { } zone && TimeZoneInfo.TryFindSystemTimeZoneById(zone, out _) ? zone : "UTC",
                PreferredLanguage = newAccount?.Language?.Trim().ToLowerInvariant() is { } language && RequestLanguage.Supported.Contains(language)
                    ? language
                    : RequestLanguage.Default,
            };

            var created = await userManager.CreateAsync(user);
            if (!created.Succeeded)
            {
                logger.LogWarning("Could not create user from {Provider} login: {Errors}", external.Provider, string.Join("; ", created.Errors.Select(e => e.Description)));
                return new ApplicationError(ErrorTypes.ExternalLoginFailed, "Could not create an account from this login.");
            }

            logger.LogInformation("Created user {UserId} from {Provider} login", user.Id, external.Provider);
        }

        var linked = await userManager.AddLoginAsync(user, new UserLoginInfo(external.Provider, external.Subject, external.Provider));
        if (!linked.Succeeded)
        {
            logger.LogWarning("Could not link {Provider} login to user {UserId}", external.Provider, user.Id);
            return new ApplicationError(ErrorTypes.ExternalLoginFailed, "Could not link this login to the account.");
        }

        await transaction.CommitAsync(cancellationToken);
        return user;
    }

    /// <summary>
    /// Anyone can register with an address they do not own and set a password; the confirmation link then goes
    /// to the real owner, who ignores it. When that owner signs in with a provider that verified the address, the
    /// account is theirs, and everything the unproven registrant set up goes first: password, 2FA and recovery
    /// codes, other sign-ins, sessions, devices (so no push reaches their phone), and any lockout or deactivation
    /// meant to keep the owner out. Otherwise the registrant would keep reading the owner's data with the password.
    /// </summary>
    private async Task<bool> ClaimUnconfirmedAsync(ApplicationUser user, ExternalIdentity external, CancellationToken cancellationToken)
    {
        async Task<bool> Step(Task<IdentityResult> step, string what)
        {
            var result = await step;
            if (!result.Succeeded)
                logger.LogError("Claiming unconfirmed user {UserId}: {What} failed: {Errors}", user.Id, what, string.Join("; ", result.Errors.Select(e => e.Code)));
            return result.Succeeded;
        }

        if (await userManager.HasPasswordAsync(user) && !await Step(userManager.RemovePasswordAsync(user), "remove password"))
            return false;

        foreach (var login in await userManager.GetLoginsAsync(user))
        {
            if (!await Step(userManager.RemoveLoginAsync(user, login.LoginProvider, login.ProviderKey), "remove login"))
                return false;
        }

        if (!await Step(userManager.SetTwoFactorEnabledAsync(user, false), "disable 2FA")
            || !await Step(userManager.ResetAuthenticatorKeyAsync(user), "reset authenticator key")
            || await userManager.GenerateNewTwoFactorRecoveryCodesAsync(user, 0) is null
            || (await userManager.IsLockedOutAsync(user) && !await Step(userManager.SetLockoutEndDateAsync(user, null), "clear lockout"))
            || !await Step(userManager.ResetAccessFailedCountAsync(user), "reset failed attempts"))
            return false;

        user.EmailConfirmed = true;
        if (!await Step(userManager.UpdateSecurityStampAsync(user), "confirm email"))
            return false;

        var sessions = await authentication.RevokeAllSessionsAsync(user.Id, "account claimed by a verified sign-in");
        var devices = await dbContext.Devices.Where(d => d.UserId == user.Id).ExecuteDeleteAsync(cancellationToken);

        logger.LogWarning(
            "Unconfirmed user {UserId} claimed by a verified {Provider} sign-in: password, 2FA and other logins removed, {Sessions} session(s) revoked, {Devices} device(s) removed",
            user.Id, external.Provider, sessions, devices
        );
        return true;
    }

    /// <summary>The verified address when it is free as a username, else one derived from the provider's subject.</summary>
    private async Task<string> PickUsernameAsync(ExternalIdentity external, string email)
    {
        if (await userManager.FindByNameAsync(email) is null)
            return email;

        return $"{external.Provider}_{external.Subject}";
    }
}

/// <summary>What the signing-in device says about its user, applied only to an account the sign-in creates.</summary>
internal sealed record NewAccountProfile(string? TimeZone, string? Language);
