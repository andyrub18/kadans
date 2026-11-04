using Kadans.SharedKernel.Users;

namespace Kadans.SharedKernel.Tests;

public class ClockFormatTests
{
    private static DateTimeOffset At(int hour, int minute) => new(2027, 1, 4, hour, minute, 0, TimeSpan.FromHours(-5));

    [Test]
    public async Task Twenty_four_hours_keep_their_leading_zero()
    {
        await Assert.That(At(21, 0).Clock(ClockFormat.TwentyFourHour)).IsEqualTo("21:00");
        await Assert.That(At(9, 5).Clock(ClockFormat.TwentyFourHour)).IsEqualTo("09:05");
        await Assert.That(At(0, 30).Clock(ClockFormat.TwentyFourHour)).IsEqualTo("00:30");
    }

    [Test]
    public async Task Twelve_hours_say_am_or_pm_and_midnight_is_twelve()
    {
        await Assert.That(At(21, 0).Clock(ClockFormat.TwelveHour)).IsEqualTo("9:00 PM");
        await Assert.That(At(9, 5).Clock(ClockFormat.TwelveHour)).IsEqualTo("9:05 AM");
        await Assert.That(At(0, 30).Clock(ClockFormat.TwelveHour)).IsEqualTo("12:30 AM");
        await Assert.That(At(12, 0).Clock(ClockFormat.TwelveHour)).IsEqualTo("12:00 PM");
    }

    [Test]
    public async Task Only_a_named_format_is_known()
    {
        await Assert.That(ClockFormats.Known(ClockFormat.TwelveHour)).IsEqualTo(ClockFormat.TwelveHour);
        await Assert.That(ClockFormats.Known(null)).IsNull();
        await Assert.That(ClockFormats.Known((ClockFormat)7)).IsNull();
    }
}
