namespace Kadans.Modules.Identity.Contracts;

public sealed record LoginRequest(string Username, string Password);

public sealed record LoginResponse(
    string AccessToken,
    DateTimeOffset ExpiresAt,
    string RefreshToken,
    DateTimeOffset RefreshTokenExpireAt
);

public sealed record RefreshTokenRequest(string RefreshToken);

public sealed record RevokeRefreshTokenRequest(string RefreshToken);

public sealed record RegisterUserRequest(
    string Username,
    string Password,
    string? Email,
    string? DisplayName = null,
    string? TimeZone = null
);

public sealed record CreateUserRequest(
    string Username,
    string Password,
    string? Email,
    IReadOnlyCollection<string>? Roles,
    string? DisplayName = null,
    string? TimeZone = null
);

public sealed record UpdateSelfUserRequest(
    string? Username,
    string? Email,
    string? NewPassword,
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
    string? DisplayName,
    string TimeZone,
    bool IsActive,
    IReadOnlyCollection<string> Roles
);
