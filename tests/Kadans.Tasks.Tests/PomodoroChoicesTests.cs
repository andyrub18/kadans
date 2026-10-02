using Kadans.Modules.Tasks.Domain;
using Kadans.Modules.Tasks.Features;
using Kadans.Modules.Tasks.Features.Pomodoro;

namespace Kadans.Tasks.Tests;

/// <summary>The server's clock decides when a phase has ended, and a manual run says "time's up" once per phase.</summary>
public class PomodoroChoicesTests
{
    private static readonly DateTimeOffset T0 = new(2027, 1, 1, 12, 0, 0, TimeSpan.Zero);

    private static PomodoroRun Start(bool autoAdvance, bool loop = false) =>
        PomodoroRun.Start(
            new Todo("Deep work", "", RecurrenceRule.CreateOneTimeRule(T0.AddYears(10)).AsT1),
            [
                new PomodoroTemplatePhase { Order = 0, Type = PomodoroPhaseType.Focus, DurationMinutes = 25 },
                new PomodoroTemplatePhase { Order = 1, Type = PomodoroPhaseType.Break, DurationMinutes = 5 },
            ],
            "user-1",
            autoAdvance,
            T0,
            loop
        );

    [Test]
    public async Task A_phase_has_ended_by_the_servers_clock_not_the_apps()
    {
        var handsFree = Start(autoAdvance: true);

        // A phone a few seconds fast says "ran out" before the server does: not yet.
        await Assert.That(handsFree.DeadlineReached(T0.AddMinutes(25).AddSeconds(-3))).IsFalse();
        await Assert.That(handsFree.DeadlineReached(T0.AddMinutes(25))).IsTrue();
        // A manual run never advances on a deadline: it waits for the person.
        await Assert.That(Start(autoAdvance: false).DeadlineReached(T0.AddHours(1))).IsFalse();
        handsFree.Pause(T0.AddMinutes(10));
        await Assert.That(handsFree.DeadlineReached(T0.AddHours(1))).IsFalse();
    }

    [Test]
    public async Task A_manual_run_says_times_up_once_per_phase()
    {
        var run = Start(autoAdvance: false);
        await Assert.That(run.TimeUpDue(T0.AddMinutes(24))).IsFalse();
        await Assert.That(run.TimeUpDue(T0.AddMinutes(25))).IsTrue();

        run.TimeUpSent();
        await Assert.That(run.TimeUpDue(T0.AddMinutes(26))).IsFalse();

        // Paused and resumed at 0:00: the same phase does not say it twice.
        run.Pause(T0.AddMinutes(27));
        run.Resume(T0.AddMinutes(28));
        await Assert.That(run.TimeUpDue(T0.AddMinutes(28))).IsFalse();

        // The next phase gets its own.
        run.Advance(null, T0.AddMinutes(29));
        await Assert.That(run.TimeUpDue(T0.AddMinutes(33))).IsFalse();
        await Assert.That(run.TimeUpDue(T0.AddMinutes(34))).IsTrue();
    }

    [Test]
    public async Task Hands_free_runs_announce_phase_changes_instead()
    {
        await Assert.That(Start(autoAdvance: true).TimeUpDue(T0.AddHours(1))).IsFalse();
    }

    [Test]
    public async Task Times_up_names_what_comes_next()
    {
        var english = LocalizedTexts.Pomodoro("en");

        var manual = Start(autoAdvance: false);
        await Assert.That(PomodoroTimeUp.Body(english, manual)).IsEqualTo("Time's up. Next: a 5-minute break, when you're ready.");
        manual.Advance(null, T0.AddMinutes(25));
        await Assert.That(PomodoroTimeUp.Body(english, manual)).IsEqualTo("Time's up. That was the last phase: finish when you're ready.");

        var looping = Start(autoAdvance: false, loop: true);
        looping.Advance(null, T0.AddMinutes(25));
        await Assert.That(PomodoroTimeUp.Body(english, looping)).IsEqualTo("Time's up. Next: 25 min of focus, when you're ready.");
        await Assert.That(PomodoroTimeUp.Body(LocalizedTexts.Pomodoro("ht"), looping)).IsEqualTo("Tan an fini. Apre sa: 25 min konsantrasyon, lè ou vle.");
    }
}
