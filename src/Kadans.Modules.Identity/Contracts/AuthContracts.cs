using Kadans.Modules.Identity.Domain;

namespace Kadans.Modules.Identity.Contracts;

public sealed record LoginRequest(string Username, string Password);

/// <summary>
/// Either a token pair, or – when <see cref="MfaRequired"/> is true – an <see cref="MfaToken"/>
/// to exchange at <c>POST /auth/mfa/verify</c> together with a TOTP or recovery code.
/// </summary>
public sealed record LoginResponse(
    string? AccessToken,
    DateTimeOffset? ExpiresAt,
    string? RefreshToken,
    DateTimeOffset? RefreshTokenExpireAt,
    bool MfaRequired = false,
    string? MfaToken = null
);

public sealed record RefreshTokenRequest(string RefreshToken);

public sealed record RevokeRefreshTokenRequest(string RefreshToken);

public sealed record ExternalLoginRequest(string Provider, string IdToken);

public sealed record MfaVerifyRequest(string MfaToken, string Code);

public sealed record RegisterUserRequest(
    string Username,
    string Password,
    string? Email,
    string? DisplayName = null,
    string? TimeZone = null
);

public sealed record ConfirmEmailRequest(string UserId, string Token);

public sealed record ResendConfirmationRequest(string Email);

public sealed record ForgotPasswordRequest(string Email);

public sealed record ResetPasswordRequest(string Email, string Token, string NewPassword);

public sealed record ChangePasswordRequest(string CurrentPassword, string NewPassword);

public sealed record ChangeEmailRequest(string NewEmail);

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
    string? TimeZone = null
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
    bool TwoFactorEnabled,
    bool IsActive,
    IReadOnlyCollection<string> Roles
);
