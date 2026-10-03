using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using Google.Apis.Auth;
using Microsoft.Extensions.Options;

namespace Kadans.Modules.Billing.Google;

/// <summary>Who may tell Kadans that a subscription changed: a Pub/Sub push, signed by Google for our endpoint.</summary>
internal interface IPushAuthenticator
{
    Task<bool> IsGooglePushAsync(string? authorization, CancellationToken cancellationToken);
}

/// <summary>
/// Pub/Sub push subscriptions with authentication send an OIDC token Google signed: for our endpoint (the audience)
/// and as the push subscription's service account. Anything else is refused: a forged notification could only make
/// Kadans read a purchase again from Google, but it should not even get that far.
/// </summary>
internal sealed class PubSubPushAuthenticator(IOptions<BillingOptions> options, ILogger<PubSubPushAuthenticator> logger) : IPushAuthenticator
{
    public async Task<bool> IsGooglePushAsync(string? authorization, CancellationToken cancellationToken)
    {
        var google = options.Value.Google;
        if (string.IsNullOrWhiteSpace(google.NotificationAudience) || string.IsNullOrWhiteSpace(google.NotificationServiceAccount))
            return false;
        if (authorization is null || !authorization.StartsWith("Bearer ", StringComparison.Ordinal))
            return false;

        try
        {
            var payload = await GoogleJsonWebSignature.ValidateAsync(
                authorization["Bearer ".Length..],
                new GoogleJsonWebSignature.ValidationSettings { Audience = [google.NotificationAudience] }
            );
            return payload.EmailVerified && string.Equals(payload.Email, google.NotificationServiceAccount, StringComparison.OrdinalIgnoreCase);
        }
        catch (InvalidJwtException e)
        {
            logger.LogWarning("A Google notification came with an invalid token: {Reason}", e.Message);
            return false;
        }
    }
}

/// <summary>A Pub/Sub push: the notification is base64 JSON in <c>message.data</c>.</summary>
internal sealed record PubSubPush(PubSubMessage? Message, string? Subscription);

internal sealed record PubSubMessage(string? Data, string? MessageId);

/// <summary>Google Play's real-time developer notification (only what Kadans reads).</summary>
internal sealed record DeveloperNotification(
    string? PackageName,
    SubscriptionNotification? SubscriptionNotification,
    JsonElement? TestNotification
)
{
    /// <summary>SUBSCRIPTION_REVOKED: refunded or taken back; access ends now, not at the expiry.</summary>
    public const int Revoked = 12;

    public static DeveloperNotification? Decode(string? base64)
    {
        if (string.IsNullOrWhiteSpace(base64))
            return null;
        try
        {
            return JsonSerializer.Deserialize<DeveloperNotification>(Encoding.UTF8.GetString(Convert.FromBase64String(base64)), Json);
        }
        catch (Exception e) when (e is FormatException or JsonException)
        {
            return null;
        }
    }

    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
}

internal sealed record SubscriptionNotification(
    int NotificationType,
    string? PurchaseToken,
    string? SubscriptionId
);
