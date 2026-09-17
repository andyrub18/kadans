using System.Text.Json;
using Kadans.SharedKernel.Errors;
using Microsoft.Extensions.Options;
using OneOf;

namespace Kadans.Modules.Identity.Security;

/// <summary>
/// Second half of the desktop sign-in: the JVM app runs Google's loopback flow with PKCE and hands
/// us the authorization code; we trade it for an ID token here, because only the server holds the
/// Desktop client's secret. The ID token then goes through the normal validator.
/// </summary>
internal sealed class GoogleCodeExchange(
    HttpClient http,
    IOptions<ExternalAuthOptions> options,
    ILogger<GoogleCodeExchange> logger
)
{
    internal const string TokenEndpoint = "https://oauth2.googleapis.com/token";

    public async Task<OneOf<ApplicationError, string>> ExchangeAsync(
        string code,
        string codeVerifier,
        string redirectUri,
        CancellationToken cancellationToken
    )
    {
        var desktop = options.Value.Google.Desktop;
        if (!desktop.IsConfigured)
        {
            return new ApplicationError(
                ErrorTypes.ExternalProviderNotConfigured,
                "Google desktop sign-in is not configured (ExternalAuth:Google:Desktop:ClientId / ClientSecret)."
            );
        }

        if (string.IsNullOrWhiteSpace(code) || string.IsNullOrWhiteSpace(codeVerifier) || !IsLoopback(redirectUri))
        {
            return new ApplicationError(
                ErrorTypes.ExternalLoginFailed,
                "An authorization code, its PKCE verifier and a loopback redirect URI are required."
            );
        }

        using var form = new FormUrlEncodedContent(
            new Dictionary<string, string>
            {
                ["grant_type"] = "authorization_code",
                ["code"] = code,
                ["code_verifier"] = codeVerifier,
                ["redirect_uri"] = redirectUri,
                ["client_id"] = desktop.ClientId!,
                ["client_secret"] = desktop.ClientSecret!,
            }
        );

        try
        {
            using var response = await http.PostAsync(TokenEndpoint, form, cancellationToken);
            var body = await response.Content.ReadAsStringAsync(cancellationToken);
            if (!response.IsSuccessStatusCode)
            {
                // Google's error body names the cause (invalid_grant, redirect_uri_mismatch…) and holds no secret.
                logger.LogWarning("Google code exchange failed: {Status} {Body}", (int)response.StatusCode, body);
                return new ApplicationError(ErrorTypes.ExternalLoginFailed, "Google did not accept the sign-in code.");
            }

            using var json = JsonDocument.Parse(body);
            if (json.RootElement.TryGetProperty("id_token", out var idToken) && idToken.GetString() is { Length: > 0 } token)
                return token;

            logger.LogWarning("Google code exchange returned no id_token (was the 'openid' scope requested?)");
            return new ApplicationError(ErrorTypes.ExternalLoginFailed, "Google returned no ID token.");
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or JsonException)
        {
            logger.LogError(ex, "Could not reach Google to exchange the sign-in code");
            return new ApplicationError(ErrorTypes.ExternalLoginFailed, "Could not reach Google to complete the sign-in.");
        }
    }

    /// <summary>Installed apps redirect to a port on the machine itself; anything else is not our flow.</summary>
    internal static bool IsLoopback(string? redirectUri) =>
        Uri.TryCreate(redirectUri, UriKind.Absolute, out var uri)
        && uri.Scheme == Uri.UriSchemeHttp
        && (uri.Host == "127.0.0.1" || uri.Host == "localhost");
}
