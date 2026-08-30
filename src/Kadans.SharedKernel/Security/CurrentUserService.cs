using System.Security.Claims;
using Microsoft.AspNetCore.Http;

namespace Kadans.SharedKernel.Security;

public interface ICurrentUserService
{
    string? UserId { get; }
}

public sealed class CurrentUserService(IHttpContextAccessor accessor) : ICurrentUserService
{
    public string? UserId => accessor.HttpContext?.User?.FindFirstValue(ClaimTypes.NameIdentifier);
}
