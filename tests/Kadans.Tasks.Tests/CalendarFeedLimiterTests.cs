using Kadans.Modules.Tasks;
using Kadans.Modules.Tasks.Features.CalendarFeed;
using Microsoft.Extensions.Options;

namespace Kadans.Tasks.Tests;

/// <summary>What one calendar feed's link may be read in an hour, wherever the reads come from.</summary>
public class CalendarFeedLimiterTests
{
    private static CalendarFeedLimiter PerHour(int reads) => new(Options.Create(new TasksOptions { CalendarFeedFetchesPerHour = reads }));

    [Test]
    public async Task A_link_is_read_as_often_as_its_hour_allows_then_told_when_to_come_back()
    {
        using var limiter = PerHour(3);
        for (var i = 0; i < 3; i++)
            await Assert.That(limiter.Allows("marie", out _)).IsTrue();

        var allowed = limiter.Allows("marie", out var retryAfter);

        await Assert.That(allowed).IsFalse();
        // One read comes back every 20 minutes.
        await Assert.That(retryAfter).IsGreaterThan(TimeSpan.FromMinutes(15));
        await Assert.That(retryAfter).IsLessThanOrEqualTo(TimeSpan.FromMinutes(20));
    }

    [Test]
    public async Task One_link_read_too_often_takes_nothing_from_another()
    {
        using var limiter = PerHour(1);
        limiter.Allows("marie", out _);

        await Assert.That(limiter.Allows("marie", out _)).IsFalse();
        await Assert.That(limiter.Allows("jean", out _)).IsTrue();
    }
}
