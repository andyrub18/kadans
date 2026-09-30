using Kadans.Modules.Identity.Features.Account;

namespace Kadans.Identity.Tests;

public class EmailThrottleTests
{
    private sealed class Clock : TimeProvider
    {
        public DateTimeOffset Now { get; set; } = new(2026, 9, 30, 12, 0, 0, TimeSpan.Zero);

        public override DateTimeOffset GetUtcNow() => Now;
    }

    [Test]
    public async Task One_mail_of_a_kind_per_address_every_two_minutes()
    {
        var clock = new Clock();
        var throttle = new EmailThrottle(clock);

        await Assert.That(throttle.TryAcquire("reset", "owner@gmail.com")).IsTrue();
        clock.Now += TimeSpan.FromSeconds(119);
        await Assert.That(throttle.TryAcquire("reset", " OWNER@gmail.com ")).IsFalse(); // same inbox, however it is typed
        clock.Now += TimeSpan.FromSeconds(1);
        await Assert.That(throttle.TryAcquire("reset", "owner@gmail.com")).IsTrue();
    }

    [Test]
    public async Task Kinds_and_addresses_do_not_hold_each_other_back()
    {
        var throttle = new EmailThrottle(new Clock());

        await Assert.That(throttle.TryAcquire("reset", "owner@gmail.com")).IsTrue();
        await Assert.That(throttle.TryAcquire("confirm", "owner@gmail.com")).IsTrue();
        await Assert.That(throttle.TryAcquire("reset", "other@gmail.com")).IsTrue();
    }
}
