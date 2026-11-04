using Kadans.SharedKernel.Users;
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

    /// <summary>"21:00" or "9:00 PM", in every app and in the server's own notifications.</summary>
    public ClockFormat ClockFormat { get; set; } = ClockFormat.TwentyFourHour;

    public DateTimeOffset CreatedAt { get; init; } = DateTimeOffset.UtcNow;
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.UtcNow;
}
