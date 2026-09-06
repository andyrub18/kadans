using Kadans.Modules.Tasks.Domain;
using Kadans.SharedKernel.Errors;

namespace Kadans.Tasks.Tests;

public class PomodoroRunTests
{
    private static readonly DateTimeOffset T0 = new(2027, 1, 1, 12, 0, 0, TimeSpan.Zero);

    private static PomodoroRun StartLooping() =>
        PomodoroRun.Start(
            new Todo("Workday", "", RecurrenceRule.CreateOneTimeRule(T0.AddYears(10)).AsT1),
            [
                new PomodoroTemplatePhase { Order = 0, Type = PomodoroPhaseType.Focus, DurationMinutes = 25 },
                new PomodoroTemplatePhase { Order = 1, Type = PomodoroPhaseType.Break, DurationMinutes = 30 },
            ],
            "user-1",
            autoAdvance: true,
            T0,
            loop: true
        );

    [Test]
    public async Task Looping_run_starts_a_new_lap_instead_of_completing()
    {
        var run = StartLooping();
        run.Advance(null, T0.AddMinutes(25));

        // Advancing past the last phase of the lap wraps instead of completing.
        await Assert.That(run.Advance(null, T0.AddMinutes(55)).IsT1).IsTrue();

        await Assert.That(run.Status).IsEqualTo(PomodoroRunStatus.Active);
        await Assert.That(run.Phases.Count).IsEqualTo(4);
        await Assert.That(run.CurrentPhaseIndex).IsEqualTo(2);
        await Assert.That(run.CycleLength).IsEqualTo(2);
        await Assert.That(run.CurrentPhase.Type).IsEqualTo(PomodoroPhaseType.Focus);
        await Assert.That(run.CurrentPhase.DurationMinutes).IsEqualTo(25);
        await Assert.That(run.PhaseEndsAt).IsEqualTo(T0.AddMinutes(80));
        await Assert.That(run.Phases[1].CompletedAt).IsEqualTo(T0.AddMinutes(55));
    }

    [Test]
    public async Task Finish_completes_a_looping_run()
    {
        var run = StartLooping();
        run.Advance(null, T0.AddMinutes(25));

        await Assert.That(run.Finish(T0.AddMinutes(40)).IsT1).IsTrue();

        await Assert.That(run.Status).IsEqualTo(PomodoroRunStatus.Completed);
        await Assert.That(run.CompletedAt).IsEqualTo(T0.AddMinutes(40));
        await Assert.That(run.PhaseEndsAt).IsNull();
        await Assert.That(run.Finish(T0.AddMinutes(41)).AsT0.ErrorType).IsEqualTo(ErrorTypes.PomodoroRunInvalidState);
    }

    [Test]
    public async Task Non_looping_run_still_completes_at_the_last_phase()
    {
        var run = Start();
        run.Advance(null, T0.AddMinutes(25));
        run.Advance(null, T0.AddMinutes(30));
        run.Advance(null, T0.AddMinutes(55));

        await Assert.That(run.Status).IsEqualTo(PomodoroRunStatus.Completed);
        await Assert.That(run.Loop).IsEqualTo(false);
    }

    private static PomodoroRun Start(bool autoAdvance = false) =>
        PomodoroRun.Start(
            new Todo("Deep work", "", RecurrenceRule.CreateOneTimeRule(T0.AddYears(10)).AsT1),
            [
                new PomodoroTemplatePhase { Order = 0, Type = PomodoroPhaseType.Focus, DurationMinutes = 25 },
                new PomodoroTemplatePhase { Order = 1, Type = PomodoroPhaseType.Break, DurationMinutes = 5 },
                new PomodoroTemplatePhase { Order = 2, Type = PomodoroPhaseType.Focus, DurationMinutes = 25 },
            ],
            "user-1",
            autoAdvance,
            T0
        );

    [Test]
    public async Task Start_anchors_the_first_phase_deadline()
    {
        var run = Start(autoAdvance: true);

        await Assert.That(run.Status).IsEqualTo(PomodoroRunStatus.Active);
        await Assert.That(run.PhaseEndsAt).IsEqualTo(T0.AddMinutes(25));
        await Assert.That(run.PausedRemaining).IsNull();
        await Assert.That(run.AutoAdvance).IsTrue();
        await Assert.That(run.Phases[0].StartedAt).IsEqualTo(T0);
        await Assert.That(run.Phases[1].StartedAt).IsNull();
    }

    [Test]
    public async Task Pause_freezes_the_remainder_and_resume_reanchors_it()
    {
        var run = Start();

        await Assert.That(run.Pause(T0.AddMinutes(10)).IsT1).IsTrue();
        await Assert.That(run.Status).IsEqualTo(PomodoroRunStatus.Paused);
        await Assert.That(run.PhaseEndsAt).IsNull();
        await Assert.That(run.PausedRemaining).IsEqualTo(TimeSpan.FromMinutes(15));

        // A long lunch later, the countdown picks up exactly where it stopped.
        await Assert.That(run.Resume(T0.AddHours(2)).IsT1).IsTrue();
        await Assert.That(run.PhaseEndsAt).IsEqualTo(T0.AddHours(2).AddMinutes(15));
        await Assert.That(run.PausedRemaining).IsNull();
    }

    [Test]
    public async Task Pause_after_the_deadline_freezes_zero_not_negative()
    {
        var run = Start();
        run.Pause(T0.AddMinutes(30));
        await Assert.That(run.PausedRemaining).IsEqualTo(TimeSpan.Zero);
    }

    [Test]
    public async Task Pause_paused_or_resume_active_are_rejected()
    {
        var run = Start();
        run.Pause(T0.AddMinutes(1));

        await Assert.That(run.Pause(T0.AddMinutes(2)).AsT0.ErrorType).IsEqualTo(ErrorTypes.PomodoroRunInvalidState);
        run.Resume(T0.AddMinutes(3));
        await Assert.That(run.Resume(T0.AddMinutes(4)).AsT0.ErrorType).IsEqualTo(ErrorTypes.PomodoroRunInvalidState);
    }

    [Test]
    public async Task Advance_moves_to_the_next_phase_with_a_fresh_deadline()
    {
        var run = Start();

        await Assert.That(run.Advance(expectedPhaseIndex: 0, T0.AddMinutes(25)).IsT1).IsTrue();

        await Assert.That(run.CurrentPhaseIndex).IsEqualTo(1);
        await Assert.That(run.PhaseEndsAt).IsEqualTo(T0.AddMinutes(30));
        await Assert.That(run.Phases[0].CompletedAt).IsEqualTo(T0.AddMinutes(25));
        await Assert.That(run.Phases[1].StartedAt).IsEqualTo(T0.AddMinutes(25));
    }

    [Test]
    public async Task Advance_with_a_stale_phase_index_is_rejected()
    {
        var run = Start();
        run.Advance(null, T0.AddMinutes(25));

        var result = run.Advance(expectedPhaseIndex: 0, T0.AddMinutes(26));

        await Assert.That(result.AsT0.ErrorType).IsEqualTo(ErrorTypes.PomodoroRunInvalidState);
        await Assert.That(run.CurrentPhaseIndex).IsEqualTo(1);
    }

    [Test]
    public async Task Advancing_the_last_phase_completes_the_run()
    {
        var run = Start();
        run.Advance(null, T0.AddMinutes(25));
        run.Advance(null, T0.AddMinutes(30));

        await Assert.That(run.Advance(null, T0.AddMinutes(55)).IsT1).IsTrue();

        await Assert.That(run.Status).IsEqualTo(PomodoroRunStatus.Completed);
        await Assert.That(run.CompletedAt).IsEqualTo(T0.AddMinutes(55));
        await Assert.That(run.PhaseEndsAt).IsNull();
        await Assert.That(run.Advance(null, T0.AddMinutes(56)).AsT0.ErrorType).IsEqualTo(ErrorTypes.PomodoroRunInvalidState);
    }

    [Test]
    public async Task Paused_runs_cannot_advance_but_can_cancel()
    {
        var run = Start();
        run.Pause(T0.AddMinutes(5));

        await Assert.That(run.Advance(null, T0.AddMinutes(6)).AsT0.ErrorType).IsEqualTo(ErrorTypes.PomodoroRunInvalidState);
        await Assert.That(run.Cancel(T0.AddMinutes(7)).IsT1).IsTrue();
        await Assert.That(run.Status).IsEqualTo(PomodoroRunStatus.Cancelled);
        await Assert.That(run.PausedRemaining).IsNull();
        await Assert.That(run.Cancel(T0.AddMinutes(8)).AsT0.ErrorType).IsEqualTo(ErrorTypes.PomodoroRunInvalidState);
    }

    [Test]
    public async Task Overdue_hands_free_advance_steps_on_the_schedule_not_on_request_time()
    {
        // 25-minute focus ends at T0+25; the advancing request lands 9 seconds late.
        var run = StartLooping();
        await Assert.That(run.Advance(0, T0.AddMinutes(25).AddSeconds(9)).IsT1).IsTrue();

        // The phase completed when it ran out, so the 30-minute break still ends at T0+55 -
        // the cadence never drifts by however late the client or the catch-up job was.
        await Assert.That(run.Phases[0].CompletedAt).IsEqualTo(T0.AddMinutes(25));
        await Assert.That(run.PhaseEndsAt).IsEqualTo(T0.AddMinutes(55));
    }

    [Test]
    public async Task Manual_run_advance_uses_the_request_time()
    {
        var run = Start();
        await Assert.That(run.Advance(0, T0.AddMinutes(30)).IsT1).IsTrue();

        // The user kept focusing past the timer; the break starts when they actually advanced.
        await Assert.That(run.Phases[0].CompletedAt).IsEqualTo(T0.AddMinutes(30));
    }
}
