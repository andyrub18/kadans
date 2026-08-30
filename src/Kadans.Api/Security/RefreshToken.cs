using Microsoft.AspNetCore.Identity;

namespace Kadans.Api.Security;

public sealed class RefreshToken
{
    public int Id { get; set; }
    public required string Token { get; set; }
    public DateTimeOffset ExpireAtUtc { get; set; }
    public DateTimeOffset CreatedAtUtc { get; set; }
    public bool IsActive { get; set; }
    public required string UserId { get; set; }
    public required ApplicationUser User { get; set; }
}
