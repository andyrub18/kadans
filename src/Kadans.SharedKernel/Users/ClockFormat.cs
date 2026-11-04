using System.Globalization;

namespace Kadans.SharedKernel.Users;

/// <summary>
/// How a person reads the time: "21:00" or "9:00 PM". One choice per account (the app's Settings), seeded from the
/// device that created it, so every device and the server's own notifications say the same.
/// </summary>
public enum ClockFormat
{
    TwentyFourHour,
    TwelveHour,
}

public static class ClockFormats
{
    /// <summary>A value the enum names, else null: a request may carry a number JSON accepts for an enum.</summary>
    public static ClockFormat? Known(ClockFormat? format) => format is { } f && Enum.IsDefined(f) ? f : null;

    /// <summary>
    /// The wall-clock time of <paramref name="local"/>: "21:00", "09:05" or "9:00 PM", "9:05 AM". The same in every
    /// language: AM/PM is how 12-hour time is written in Haiti too.
    /// </summary>
    public static string Clock(this DateTimeOffset local, ClockFormat format) =>
        local.ToString(format == ClockFormat.TwelveHour ? "h:mm tt" : "HH:mm", CultureInfo.InvariantCulture);
}
