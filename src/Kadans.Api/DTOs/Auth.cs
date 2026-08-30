namespace Kadans.Api.DTOs;

public sealed record LoginRequest(string Username, string Password);

public sealed record LoginResponse(
    string AccessToken,
    DateTimeOffset ExpiresAt,
    string RefreshToken,
    DateTimeOffset RefreshTokenExpireAt
);

public sealed record RefreshTokenRequest(string RefreshToken);

public sealed record RevokeRefreshTokenRequest(string RefreshToken);

public sealed record RegisterUserRequest(string Username, string Password, string? Email);

public sealed record CreateUserRequest(
    string Username,
    string Password,
    string? Email,
    IReadOnlyCollection<string>? Roles
);

public sealed record UpdateSelfUserRequest(string? Username, string? Email, string? NewPassword);

public sealed record UpdateUserRequest(
    string? Username,
    string? Email,
    string? NewPassword,
    IReadOnlyCollection<string>? Roles
);

public sealed record UserResponse(
    string Id,
    string Username,
    string? Email,
    bool IsActive,
    IReadOnlyCollection<string> Roles
);
