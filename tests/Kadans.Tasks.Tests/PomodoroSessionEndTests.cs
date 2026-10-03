using Kadans.Modules.Tasks.Domain;
using Kadans.SharedKernel.Errors;

namespace Kadans.Tasks.Tests;

/// <summary>A session ends by itself at its end time, and its phases count the time really spent.</summary>
public class PomodoroSessionEndTests
{
    private static readonly DateTimeOffset T0 = new(2027, 1, 1, 9, 0, 0, TimeSpan.Zero);

    private static PomodoroRun Start(bool autoAdvance = true, DateTimeOffset? finishBy = null) =>
        PomodoroRun.Start(
            new Todo("Workday", "", RecurrenceRule.CreateOneTimeRule(T0.AddYears(10)).AsT1),
            [
                new PomodoroTemplatePhase { Order = 0, Type = PomodoroPhaseType.Focus, DurationMinutes = 15 },
                new PomodoroTemplatePhase { Order = 1, Type = PomodoroPhaseType.Break, DurationMinutes = 5 },
            ],
            "user-1",
            autoAdvance,
            T0,
            loop: true,
            finishBy
        );

    [Test]
    public async Task A_session_ends_twelve_hours_after_its_start_unless_told_otherwise()
    {
        await Assert.That(Start().FinishBy).IsEqualTo(T0.AddHours(12));
        await Assert.That(Start(finishBy: T0.AddHours(8)).FinishBy).IsEqualTo(T0.AddHours(8));
    }

    [Test]
    public async Task An_end_is_between_a_minute_and_a_day_away()
    {
        await Assert.That(PomodoroRun.CheckFinishBy(T0.AddMinutes(1), T0)).IsNull();
        await Assert.That(PomodoroRun.CheckFinishBy(T0.AddHours(24), T0)).IsNull();
        await Assert.That(PomodoroRun.CheckFinishBy(T0.AddSeconds(30), T0)!.ErrorType).IsEqualTo(ErrorTypes.ValidationError);
        await Assert.That(PomodoroRun.CheckFinishBy(T0.AddHours(25), T0)).IsNotNull();
        await Assert.That(PomodoroRun.CheckFinishBy(T0.AddHours(-1), T0)).IsNotNull();
    }

    [Test]
    public async Task Working_later_moves_the_end()
    {
        var run = Start(finishBy: T0.AddHours(8));

        await Assert.That(run.ChangeFinishBy(T0.AddHours(9), T0.AddHours(7)).IsT1).IsTrue();
        await Assert.That(run.FinishBy).IsEqualTo(T0.AddHours(9));
        await Assert.That(run.FinishDue(T0.AddHours(8).AddMinutes(30))).IsFalse();
        await Assert.That(run.FinishDue(T0.AddHours(9))).IsTrue();
        // Too far, or already past.
        await Assert.That(run.ChangeFinishBy(T0.AddHours(40), T0.AddHours(7)).IsT0).IsTrue();
        run.Finish(T0.AddHours(8));
        await Assert.That(run.ChangeFinishBy(T0.AddHours(10), T0.AddHours(8)).IsT0).IsTrue();
        await Assert.That(run.FinishDue(T0.AddHours(20))).IsFalse();
    }

    [Test]
    public async Task Finishing_counts_the_phase_under_way()
    {
        var run = Start();
        run.Finish(T0.AddMinutes(10)); // 10 minutes into a 15-minute focus

        await Assert.That(run.Status).IsEqualTo(PomodoroRunStatus.Completed);
        await Assert.That(run.Phases[0].CompletedAt).IsEqualTo(T0.AddMinutes(10));
        await Assert.That(run.Phases[0].ActualSeconds).IsEqualTo(600);
    }

    [Test]
    public async Task Time_spent_leaves_pauses_out_and_counts_what_was_really_done()
    {
        var run = Start(autoAdvance: false);
        run.Pause(T0.AddMinutes(4));
        run.Resume(T0.AddMinutes(10)); // 6 minutes on a call
        run.Advance(null, T0.AddMinutes(12)); // skipped after 6 minutes of focus

        await Assert.That(run.Phases[0].PausedSeconds).IsEqualTo(360);
        await Assert.That(run.Phases[0].ActualSeconds).IsEqualTo(360);

        // Finished while paused: the pause it ends in does not count either.
        run.Pause(T0.AddMinutes(14));
        run.Finish(T0.AddMinutes(30));
        await Assert.That(run.Phases[1].ActualSeconds).IsEqualTo(120);
    }

    [Test]
    public async Task A_phase_kept_going_past_its_timer_counts_the_whole_time()
    {
        var run = Start(autoAdvance: false);
        run.Advance(null, T0.AddMinutes(20)); // focused 5 minutes past the 15

        await Assert.That(run.Phases[0].ActualSeconds).IsEqualTo(20 * 60);
    }
}
