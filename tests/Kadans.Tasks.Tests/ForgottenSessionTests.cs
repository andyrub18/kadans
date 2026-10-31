using Kadans.Modules.Tasks.Contracts;
using Kadans.Modules.Tasks.Domain;
using Kadans.Modules.Tasks.Features;
using Kadans.Modules.Tasks.Features.Pomodoro;

namespace Kadans.Tasks.Tests;

/// <summary>
/// A session nobody watches any more: it waits at the end of each cycle for "Another cycle" (or "Done"), ends when the
/// todo's next occurrence starts, and counts only what was really done.
/// </summary>
public class ForgottenSessionTests
{
    private static readonly DateTimeOffset T0 = new(2027, 1, 1, 9, 0, 0, TimeSpan.Zero);
    private static readonly Guid Occurrence = Guid.CreateVersion7();

    /// <summary>25 minutes of focus and a 5-minute break, repeated: one cycle is 30 minutes.</summary>
    private static PomodoroRun Start(bool autoAdvance = true) =>
        PomodoroRun.Start(
            new Todo("Study", "", RecurrenceRule.CreateOneTimeRule(T0.AddYears(10)).AsT1),
            [
                new PomodoroTemplatePhase { Order = 0, Type = PomodoroPhaseType.Focus, DurationMinutes = 25 },
                new PomodoroTemplatePhase { Order = 1, Type = PomodoroPhaseType.Break, DurationMinutes = 5 },
            ],
            "user-1",
            autoAdvance,
            T0,
            loop: true,
            occurrenceId: Occurrence
        );

    /// <summary>The first cycle runs out by itself, as the deadline watcher steps it.</summary>
    private static PomodoroRun AfterOneCycle()
    {
        var run = Start();
        run.Advance(null, T0.AddMinutes(25), onItsOwn: true);
        run.Advance(null, T0.AddMinutes(30), onItsOwn: true);
        return run;
    }

    private static Kadans.SharedKernel.Notifications.NotificationMessage Notice(PomodoroRun run, string language = "en")
    {
        var phase = run.CurrentPhase;
        return PomodoroAutoAdvancer.PhaseMessage(
            LocalizedTexts.Pomodoro(language), "Study", run.TodoId, run.Id, run.OccurrenceId,
            run.Status, phase.Type, phase.DurationMinutes, run.CurrentPhaseIndex, run.CycleLength, run.Loop, run.IsBetweenLaps
        );
    }

    [Test]
    public async Task A_hands_free_cycle_that_runs_out_waits_for_the_person()
    {
        var run = AfterOneCycle();

        await Assert.That(run.Status).IsEqualTo(PomodoroRunStatus.Paused);
        await Assert.That(run.IsBetweenLaps).IsTrue();
        await Assert.That(run.CurrentPhaseIndex).IsEqualTo(2);
        await Assert.That(run.CurrentPhase.StartedAt).IsNull();
        await Assert.That(run.PausedAt).IsEqualTo(T0.AddMinutes(30));
        await Assert.That(run.PausedRemaining).IsEqualTo(TimeSpan.FromMinutes(25));
        await Assert.That(run.PhaseEndsAt).IsNull();
        await Assert.That(run.Phases[1].CompletedAt).IsEqualTo(T0.AddMinutes(30));
        // Nothing more advances on its own: the watcher only steps active runs.
        await Assert.That(run.DeadlineReached(T0.AddHours(5))).IsFalse();
    }

    [Test]
    public async Task Its_notice_asks_for_another_cycle_and_carries_what_its_buttons_need()
    {
        var run = AfterOneCycle();

        var notice = Notice(run);

        await Assert.That(notice.Body).IsEqualTo("Cycle 1 done. Another one? The session waits for you.");
        await Assert.That(notice.Data![PomodoroAutoAdvancer.CycleDoneKey]).IsEqualTo("1");
        await Assert.That(notice.Data["runId"]).IsEqualTo(run.Id.ToString());
        await Assert.That(notice.Data["occurrenceId"]).IsEqualTo(Occurrence.ToString());
        await Assert.That(notice.Data["link"]).IsEqualTo($"kadans://todos/{run.TodoId}/focus");
        await Assert.That(notice.ShownByApp).IsTrue();
    }

    [Test]
    [Arguments("fr", "Cycle 1 terminé. Un autre ? La session vous attend.")]
    [Arguments("ht", "Sik 1 fini. Yon lòt? Seyans lan ap tann ou.")]
    public async Task The_notice_is_in_the_persons_language(string language, string expected)
    {
        await Assert.That(Notice(AfterOneCycle(), language).Body).IsEqualTo(expected);
    }

    [Test]
    public async Task Phase_changes_inside_a_cycle_ask_nothing()
    {
        var run = Start();
        run.Advance(null, T0.AddMinutes(25), onItsOwn: true);

        var notice = Notice(run);

        await Assert.That(notice.Body).IsEqualTo("Break — 5 min");
        await Assert.That(notice.Data!.ContainsKey(PomodoroAutoAdvancer.CycleDoneKey)).IsFalse();
        await Assert.That(notice.ShownByApp).IsFalse();
    }

    [Test]
    public async Task Next_pressed_at_the_end_of_a_cycle_goes_straight_on()
    {
        var run = Start();
        run.Advance(null, T0.AddMinutes(25), onItsOwn: true);
        run.Advance(null, T0.AddMinutes(28)); // the person skips the rest of the break: they are there

        await Assert.That(run.Status).IsEqualTo(PomodoroRunStatus.Active);
        await Assert.That(run.IsBetweenLaps).IsFalse();
        await Assert.That(run.CurrentPhase.StartedAt).IsEqualTo(T0.AddMinutes(28));
        await Assert.That(Notice(run).Data!.ContainsKey(PomodoroAutoAdvancer.CycleDoneKey)).IsFalse();
    }

    [Test]
    public async Task A_manual_session_moves_on_only_when_the_person_does_as_before()
    {
        var run = Start(autoAdvance: false);
        run.Advance(null, T0.AddMinutes(25));
        run.Advance(null, T0.AddMinutes(31));

        await Assert.That(run.Status).IsEqualTo(PomodoroRunStatus.Active);
        await Assert.That(run.CurrentPhaseIndex).IsEqualTo(2);
    }

    [Test]
    public async Task Another_cycle_starts_it_when_the_person_comes_back()
    {
        var run = AfterOneCycle();
        var back = T0.AddMinutes(42); // a longer break than planned

        await Assert.That(run.Resume(back).IsT1).IsTrue();

        await Assert.That(run.Status).IsEqualTo(PomodoroRunStatus.Active);
        await Assert.That(run.IsBetweenLaps).IsFalse();
        await Assert.That(run.CurrentPhase.StartedAt).IsEqualTo(back);
        await Assert.That(run.CurrentPhase.PausedSeconds).IsEqualTo(0);
        await Assert.That(run.PhaseEndsAt).IsEqualTo(back.AddMinutes(25));
        run.Advance(null, back.AddMinutes(25), onItsOwn: true);
        await Assert.That(run.Phases[2].ActualSeconds).IsEqualTo(25 * 60);
    }

    [Test]
    public async Task Nobody_came_back_so_the_session_ended_with_its_last_cycle()
    {
        var run = AfterOneCycle();

        await Assert.That(run.Finish(T0.AddHours(8), onItsOwn: true).IsT1).IsTrue(); // the next occurrence began

        await Assert.That(run.Status).IsEqualTo(PomodoroRunStatus.Completed);
        await Assert.That(run.CompletedAt).IsEqualTo(T0.AddMinutes(30));
        await Assert.That(run.Phases[2].ActualSeconds).IsNull();
        await Assert.That(run.Phases.Sum(p => p.ActualSeconds ?? 0)).IsEqualTo(30 * 60);
    }

    [Test]
    public async Task Done_or_finish_pressed_between_cycles_ends_it_then()
    {
        var run = AfterOneCycle();

        run.Finish(T0.AddMinutes(33));

        await Assert.That(run.CompletedAt).IsEqualTo(T0.AddMinutes(33));
        await Assert.That(run.Phases[2].ActualSeconds).IsNull();
    }

    [Test]
    public async Task A_phase_left_at_zero_counts_up_to_its_timer_when_nobody_finishes()
    {
        var run = Start(autoAdvance: false); // its 25 minutes ran out at 9:25 and it was never touched again

        run.Finish(T0.AddHours(5), onItsOwn: true);

        await Assert.That(run.Phases[0].CompletedAt).IsEqualTo(T0.AddMinutes(25));
        await Assert.That(run.Phases[0].ActualSeconds).IsEqualTo(25 * 60);
    }

    [Test]
    public async Task Finish_pressed_late_still_counts_the_time_kept_going()
    {
        var run = Start(autoAdvance: false);

        run.Finish(T0.AddMinutes(40));

        await Assert.That(run.Phases[0].ActualSeconds).IsEqualTo(40 * 60);
    }

    [Test]
    public async Task The_next_occurrence_ends_the_session_when_it_comes_first()
    {
        var run = Start(); // ends by itself at 21:00
        var next = T0.AddHours(8); // the 17:00 one

        await Assert.That(run.EndsAt(next)).IsEqualTo(next);
        await Assert.That(run.EndsAt(T0.AddHours(13))).IsEqualTo(T0.AddHours(12));
        await Assert.That(run.EndsAt(null)).IsEqualTo(T0.AddHours(12));
        await Assert.That(run.FinishDue(next.AddSeconds(-1), next)).IsFalse();
        await Assert.That(run.FinishDue(next, next)).IsTrue();
        await Assert.That(run.FinishDue(next)).IsFalse();
    }

    [Test]
    public async Task The_app_sees_the_end_it_will_have_and_why()
    {
        var run = AfterOneCycle();

        var ends = run.ToResponse(T0.AddHours(8));
        await Assert.That(ends.FinishAt).IsEqualTo(T0.AddHours(8));
        await Assert.That(ends.EndsWithNextOccurrence).IsTrue();
        await Assert.That(ends.BetweenLaps).IsTrue();

        var later = run.ToResponse(T0.AddHours(13));
        await Assert.That(later.FinishAt).IsEqualTo(T0.AddHours(12));
        await Assert.That(later.EndsWithNextOccurrence).IsFalse();

        run.Finish(T0.AddMinutes(40));
        var done = run.ToResponse(T0.AddHours(8));
        await Assert.That(done.BetweenLaps).IsFalse();
        await Assert.That(done.EndsWithNextOccurrence).IsFalse();
    }
}
