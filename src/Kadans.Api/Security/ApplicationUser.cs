using Microsoft.AspNetCore.Identity;

namespace Kadans.Api.Security;

public sealed class ApplicationUser : IdentityUser
{
    public string? DisplayName { get; set; }

    /// <summary>
    /// IANA time zone the user lives in. Default for new recurrence rules and for
    /// rendering notification times.
    /// </summary>
    public string TimeZoneId { get; set; } = "UTC";

    public DateTimeOffset CreatedAt { get; init; } = DateTimeOffset.UtcNow;
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.UtcNow;
}
