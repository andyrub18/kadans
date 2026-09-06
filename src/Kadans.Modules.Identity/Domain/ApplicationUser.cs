using Microsoft.AspNetCore.Identity;

namespace Kadans.Modules.Identity.Domain;

internal sealed class ApplicationUser : IdentityUser
{
    public string? DisplayName { get; set; }

    /// <summary>
    /// IANA time zone the user lives in. Default for new recurrence rules and for
    /// rendering notification times.
    /// </summary>
    public string TimeZoneId { get; set; } = "UTC";

    /// <summary>UI/notification language: en, fr or ht.</summary>
    public string PreferredLanguage { get; set; } = "en";

    public DateTimeOffset CreatedAt { get; init; } = DateTimeOffset.UtcNow;
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.UtcNow;
}
