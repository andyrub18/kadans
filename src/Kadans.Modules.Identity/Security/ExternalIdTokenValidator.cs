using System.Collections.Concurrent;
using Kadans.SharedKernel.Errors;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.JsonWebTokens;
using Microsoft.IdentityModel.Protocols;
using Microsoft.IdentityModel.Protocols.OpenIdConnect;
using Microsoft.IdentityModel.Tokens;
using OneOf;

namespace Kadans.Modules.Identity.Security;

internal sealed record ExternalIdentity(
    string Provider,
    string Subject,
    string? Email,
    bool EmailVerified,
    string? DisplayName
);

/// <summary>
/// Verifies ID tokens obtained natively by the clients (Google Sign-In, Sign in with Apple)
/// against the provider's published signing keys. No browser redirects on the server.
/// </summary>
internal sealed class ExternalIdTokenValidator(
    IOptions<ExternalAuthOptions> options,
    ILogger<ExternalIdTokenValidator> logger
)
{
    public const string Google = "google";
    public const string Apple = "apple";

    private static readonly IReadOnlyDictionary<string, (string Metadata, string[] Issuers)> Providers =
        new Dictionary<string, (string, string[])>
        {
            [Google] = (
                "https://accounts.google.com/.well-known/openid-configuration",
                ["https://accounts.google.com", "accounts.google.com"]
            ),
            [Apple] = (
                "https://appleid.apple.com/.well-known/openid-configuration",
                ["https://appleid.apple.com"]
            ),
        };

    private readonly ConcurrentDictionary<string, ConfigurationManager<OpenIdConnectConfiguration>> managers = new();
    private readonly JsonWebTokenHandler handler = new() { MapInboundClaims = false };

    public async Task<OneOf<ApplicationError, ExternalIdentity>> ValidateAsync(
        string provider,
        string idToken,
        CancellationToken cancellationToken
    )
    {
        provider = provider.Trim().ToLowerInvariant();
        if (!Providers.TryGetValue(provider, out var known))
        {
            return new ApplicationError(
                ErrorTypes.ExternalProviderNotConfigured,
                $"Unsupported provider '{provider}'. Supported: google, apple."
            );
        }

        var clientIds = provider == Google ? options.Value.Google.ClientIds : options.Value.Apple.ClientIds;
        if (clientIds.Count == 0)
        {
            return new ApplicationError(
                ErrorTypes.ExternalProviderNotConfigured,
                $"No client ids configured for '{provider}' (ExternalAuth:{provider}:ClientIds)."
            );
        }

        var manager = managers.GetOrAdd(
            provider,
            static (_, metadata) =>
                new ConfigurationManager<OpenIdConnectConfiguration>(
                    metadata,
                    new OpenIdConnectConfigurationRetriever()
                ),
            known.Metadata
        );

        OpenIdConnectConfiguration configuration;
        try
        {
            configuration = await manager.GetConfigurationAsync(cancellationToken);
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Could not fetch OpenID configuration for {Provider}", provider);
            return new ApplicationError(
                ErrorTypes.ExternalLoginFailed,
                $"Could not reach {provider} to verify the token."
            );
        }

        var result = await handler.ValidateTokenAsync(
            idToken,
            new TokenValidationParameters
            {
                ValidIssuers = known.Issuers,
                ValidAudiences = clientIds,
                IssuerSigningKeys = configuration.SigningKeys,
                ValidateIssuer = true,
                ValidateAudience = true,
                ValidateLifetime = true,
                ValidateIssuerSigningKey = true,
                ClockSkew = TimeSpan.FromMinutes(2),
            }
        );

        if (!result.IsValid)
        {
            // A stale key set is the usual cause after a provider rotates keys.
            manager.RequestRefresh();
            logger.LogWarning(result.Exception, "Rejected {Provider} ID token", provider);
            return new ApplicationError(ErrorTypes.ExternalLoginFailed, "The ID token is not valid.");
        }

        var identity = result.ClaimsIdentity;
        var subject = identity.FindFirst("sub")?.Value;
        if (string.IsNullOrEmpty(subject))
            return new ApplicationError(ErrorTypes.ExternalLoginFailed, "The ID token has no subject.");

        var emailVerified = identity.FindFirst("email_verified")?.Value;

        return new ExternalIdentity(
            provider,
            subject,
            identity.FindFirst("email")?.Value,
            string.Equals(emailVerified, "true", StringComparison.OrdinalIgnoreCase),
            identity.FindFirst("name")?.Value
        );
    }
}
