using Kadans.Modules.Identity.Contracts;
using Kadans.Modules.Identity.Domain;
using Kadans.Modules.Identity.Security;
using Kadans.SharedKernel.Errors;
using Microsoft.AspNetCore.Identity;
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

        var external = validation.AsT1;

        var user = await userManager.FindByLoginAsync(external.Provider, external.Subject);
        if (user is null)
        {
            var linkResult = await LinkOrCreateAsync(external);
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

        return await SignIn(new ExternalLoginRequest(ExternalIdTokenValidator.Google, exchanged.AsT1), cancellationToken);
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

    private async Task<OneOf<ApplicationError, ApplicationUser>> LinkOrCreateAsync(ExternalIdentity external)
    {
        ApplicationUser? user = null;

        // Same verified email as an existing account: attach the login to it instead of creating a duplicate.
        if (external.EmailVerified && !string.IsNullOrWhiteSpace(external.Email))
            user = await userManager.FindByEmailAsync(external.Email);

        if (user is null)
        {
            user = new ApplicationUser
            {
                UserName = await PickUsernameAsync(external),
                Email = external.Email,
                EmailConfirmed = external.EmailVerified,
                DisplayName = external.DisplayName,
                LockoutEnabled = true,
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

        return user;
    }

    private async Task<string> PickUsernameAsync(ExternalIdentity external)
    {
        if (!string.IsNullOrWhiteSpace(external.Email) && await userManager.FindByNameAsync(external.Email) is null)
            return external.Email;

        return $"{external.Provider}_{external.Subject}";
    }
}
