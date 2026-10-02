using System.Security.Claims;
using Microsoft.AspNetCore.Http;

namespace Kadans.SharedKernel.Security;

public interface ICurrentUserService
{
    string? UserId { get; }

    /// <summary>The sign-in session the request's access token belongs to (<see cref="SessionClaim"/>), or null.</summary>
    string? SessionId { get; }
}

public sealed class CurrentUserService(IHttpContextAccessor accessor) : ICurrentUserService
{
    public string? UserId => accessor.HttpContext?.User?.FindFirstValue(ClaimTypes.NameIdentifier);

    public string? SessionId => accessor.HttpContext?.User?.FindFirstValue(SessionClaim.Type);
}

/// <summary>
/// Every access token names the sign-in session it was issued for: one session per sign-in on one device,
/// kept across refreshes. The API refuses a token whose session has ended, and the hub drops its connections.
/// </summary>
public static class SessionClaim
{
    public const string Type = ClaimTypes.Sid;
}
