using Kadans.Modules.Tasks.Features.Pomodoro;

namespace Kadans.Tasks.Tests;

public class PomodoroDeadlineWatcherTests
{
    private static readonly DateTimeOffset Now = new(2026, 9, 17, 12, 0, 0, TimeSpan.Zero);
    private static readonly TimeSpan Fallback = TimeSpan.FromSeconds(5);

    [Test]
    public async Task Sleeps_exactly_until_a_near_deadline()
    {
        var delay = PomodoroDeadlineWatcher.NextDelay(Now, Now.AddMilliseconds(1_250), Fallback);

        await Assert.That(delay).IsEqualTo(TimeSpan.FromMilliseconds(1_250));
    }

    [Test]
    public async Task A_far_deadline_or_none_at_all_still_rechecks_after_the_fallback()
    {
        await Assert.That(PomodoroDeadlineWatcher.NextDelay(Now, Now.AddMinutes(25), Fallback)).IsEqualTo(Fallback);
        await Assert.That(PomodoroDeadlineWatcher.NextDelay(Now, null, Fallback)).IsEqualTo(Fallback);
    }

    [Test]
    public async Task A_deadline_already_passed_never_becomes_a_busy_loop()
    {
        var delay = PomodoroDeadlineWatcher.NextDelay(Now, Now.AddSeconds(-3), Fallback);

        await Assert.That(delay).IsEqualTo(TimeSpan.FromMilliseconds(20));
    }

    [Test]
    public async Task A_pulse_ends_the_wait_long_before_the_timeout()
    {
        var signal = new PomodoroDeadlineSignal();
        var started = DateTimeOffset.UtcNow;

        var waiting = signal.WaitAsync(TimeSpan.FromSeconds(30), CancellationToken.None);
        signal.Pulse();
        signal.Pulse(); // extra pulses collapse instead of piling up
        await waiting;

        await Assert.That(DateTimeOffset.UtcNow - started).IsLessThan(TimeSpan.FromSeconds(5));
    }

    [Test]
    public async Task Without_a_pulse_the_wait_ends_on_its_own()
    {
        var signal = new PomodoroDeadlineSignal();
        var started = DateTimeOffset.UtcNow;

        await signal.WaitAsync(TimeSpan.FromMilliseconds(50), CancellationToken.None); // a timeout is not an error

        await Assert.That(DateTimeOffset.UtcNow - started).IsGreaterThanOrEqualTo(TimeSpan.FromMilliseconds(40));
    }
}
