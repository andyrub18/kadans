using Kadans.Modules.Identity.Domain;

namespace Kadans.Modules.Identity.Contracts;

public sealed record LoginRequest(string Username, string Password);

/// <summary>
/// Either a token pair, or – when <see cref="MfaRequired"/> is true – an <see cref="MfaToken"/>
/// to exchange at <c>POST /auth/mfa/verify</c> together with a TOTP or recovery code.
/// </summary>
/// <param name="DeletionScheduled">
/// The account is closed, awaiting erasure on <paramref name="EraseAfter"/>: no session, only
/// <paramref name="RestoreToken"/>, which can keep it (<c>POST /auth/restore-account</c>).
/// </param>
public sealed record LoginResponse(
    string? AccessToken,
    DateTimeOffset? ExpiresAt,
    string? RefreshToken,
    DateTimeOffset? RefreshTokenExpireAt,
    bool MfaRequired = false,
    string? MfaToken = null,
    bool DeletionScheduled = false,
    DateTimeOffset? EraseAfter = null,
    string? RestoreToken = null
);

/// <param name="CurrentPassword">Required when the account has one; an account that only uses Google confirms by email.</param>
public sealed record DeleteAccountRequest(string? CurrentPassword = null);

/// <summary>Either the erasure date (closed now), or <paramref name="ConfirmationSentTo"/>: a link to confirm went there.</summary>
public sealed record DeleteAccountResponse(DateTimeOffset? EraseAfter, string? ConfirmationSentTo = null);

public sealed record RestoreAccountRequest(string RestoreToken);

public sealed record RefreshTokenRequest(string RefreshToken);

public sealed record RevokeRefreshTokenRequest(string RefreshToken);

/// <summary><c>TimeZone</c> and <c>Language</c> are the device's; they seed the account only if this sign-in creates it.</summary>
public sealed record ExternalLoginRequest(string Provider, string IdToken, string? TimeZone = null, string? Language = null);

/// <summary>Desktop loopback flow: the code Google redirected to the app, plus its PKCE verifier.</summary>
public sealed record GoogleCodeLoginRequest(string Code, string CodeVerifier, string RedirectUri, string? TimeZone = null, string? Language = null);

/// <summary>Which external sign-ins this server can complete; a null provider means "hide the button".</summary>
public sealed record AuthProvidersResponse(GoogleProviderResponse? Google);

/// <summary>Public OAuth client ids per platform (never the secret). Null = that platform is not set up.</summary>
public sealed record GoogleProviderResponse(string? DesktopClientId, string? WebClientId);

public sealed record MfaVerifyRequest(string MfaToken, string Code);

public sealed record RegisterUserRequest(
    string Username,
    string Password,
    string? Email,
    string? DisplayName = null,
    string? TimeZone = null,
    string? Language = null
);

public sealed record ConfirmEmailRequest(string UserId, string Token);

public sealed record ResendConfirmationRequest(string Email);

public sealed record ForgotPasswordRequest(string Email);

public sealed record ResetPasswordRequest(string Email, string Token, string NewPassword);

public sealed record ChangePasswordRequest(string CurrentPassword, string NewPassword);

/// <param name="CurrentPassword">Required when the account has a password (not for accounts that only use Google).</param>
public sealed record ChangeEmailRequest(string NewEmail, string? CurrentPassword = null);

public sealed record ConfirmEmailChangeRequest(string NewEmail, string Token);

public sealed record MfaEnrollResponse(string SharedKey, string AuthenticatorUri);

public sealed record MfaCodeRequest(string Code);

public sealed record RecoveryCodesResponse(IReadOnlyList<string> Codes);

public sealed record RegisterDeviceRequest(
    DevicePlatform Platform,
    string Name,
    string? PushToken = null,
    string? AppVersion = null
);

public sealed record DeviceResponse(
    Guid InstallationId,
    DevicePlatform Platform,
    string Name,
    bool HasPushToken,
    string? AppVersion,
    DateTimeOffset RegisteredAt,
    DateTimeOffset LastSeenAt
);

public sealed record CreateUserRequest(
    string Username,
    string Password,
    string? Email,
    IReadOnlyCollection<string>? Roles,
    string? DisplayName = null,
    string? TimeZone = null
);

/// <summary>Self-service profile update. Email and password have their own verified flows.</summary>
public sealed record UpdateSelfUserRequest(
    string? Username,
    string? DisplayName = null,
    string? TimeZone = null,
    string? Language = null
);

public sealed record UpdateUserRequest(
    string? Username,
    string? Email,
    string? NewPassword,
    IReadOnlyCollection<string>? Roles,
    string? DisplayName = null,
    string? TimeZone = null
);

public sealed record UserResponse(
    string Id,
    string Username,
    string? Email,
    bool EmailConfirmed,
    string? DisplayName,
    string TimeZone,
    string Language,
    bool TwoFactorEnabled,
    bool IsActive,
    IReadOnlyCollection<string> Roles,
    /// <summary>False for an account that only signs in with Google: nothing to confirm a change with.</summary>
    bool HasPassword = true
);
