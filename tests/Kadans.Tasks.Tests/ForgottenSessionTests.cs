using Kadans.Modules.Tasks.Contracts;
using Kadans.Modules.Tasks.Domain;
using Kadans.Modules.Tasks.Features;
using Kadans.Modules.Tasks.Features.Pomodoro;

namespace Kadans.Tasks.Tests;

/// <summary>
/// A session nobody watches any more: each new cycle's notice offers to finish it, it ends when the todo's next
/// occurrence starts, and it counts only what was really done.
/// </summary>
public class ForgottenSessionTests
{
    private static readonly DateTimeOffset T0 = new(2027, 1, 1, 9, 0, 0, TimeSpan.Zero);

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
            loop: true
        );

    private static NotificationMessageView Notice(PomodoroRun run, string language = "en")
    {
        var phase = run.CurrentPhase;
        var message = PomodoroAutoAdvancer.PhaseMessage(
            LocalizedTexts.Pomodoro(language), "Study", run.TodoId, run.Id,
            run.Status, phase.Type, phase.DurationMinutes, run.CurrentPhaseIndex, run.CycleLength, run.Loop
        );
        return new(message.Body, message.Data!.GetValueOrDefault(PomodoroAutoAdvancer.CycleDoneKey), message.ShownByApp);
    }

    private sealed record NotificationMessageView(string Body, string? CycleDone, bool ShownByApp);

    [Test]
    public async Task A_new_cycle_begins_by_itself_and_its_notice_asks_whether_the_person_is_done()
    {
        var run = Start();
        run.Advance(null, T0.AddMinutes(25));
        await Assert.That(Notice(run).CycleDone).IsNull(); // the break inside the cycle: nothing to ask

        run.Advance(null, T0.AddMinutes(30));

        await Assert.That(run.Status).IsEqualTo(PomodoroRunStatus.Active);
        await Assert.That(run.CurrentPhase.StartedAt).IsEqualTo(T0.AddMinutes(30));
        var notice = Notice(run);
        await Assert.That(notice.Body).IsEqualTo("Cycle 1 done, the next one begins (25 min of focus). Finished with the task?");
        await Assert.That(notice.CycleDone).IsEqualTo("1");
        await Assert.That(notice.ShownByApp).IsTrue();
    }

    [Test]
    [Arguments("fr", "Cycle 2 terminé, le suivant commence (25 min de concentration). Vous avez fini ?")]
    [Arguments("ht", "Sik 2 fini, pwochen an kòmanse (25 min konsantrasyon). Ou fini?")]
    public async Task The_notice_counts_the_cycles_in_the_persons_language(string language, string expected)
    {
        var run = Start();
        for (var minutes = 25; minutes <= 60; minutes += minutes % 30 == 0 ? 25 : 5)
            run.Advance(null, T0.AddMinutes(minutes));

        await Assert.That(run.CurrentPhaseIndex).IsEqualTo(4);
        await Assert.That(Notice(run, language).Body).IsEqualTo(expected);
        await Assert.That(Notice(run, language).CycleDone).IsEqualTo("2");
    }

    [Test]
    public async Task Other_phase_changes_and_a_session_that_does_not_repeat_ask_nothing()
    {
        await Assert.That(PomodoroAutoAdvancer.CycleDone(PomodoroRunStatus.Active, 1, 2, loop: true)).IsNull();
        await Assert.That(PomodoroAutoAdvancer.CycleDone(PomodoroRunStatus.Active, 2, 2, loop: false)).IsNull();
        await Assert.That(PomodoroAutoAdvancer.CycleDone(PomodoroRunStatus.Completed, 2, 2, loop: true)).IsNull();
        await Assert.That(PomodoroAutoAdvancer.CycleDone(PomodoroRunStatus.Active, 0, 2, loop: true)).IsNull();
        await Assert.That(PomodoroAutoAdvancer.CycleDone(PomodoroRunStatus.Active, 6, 2, loop: true)).IsEqualTo(3);
        await Assert.That(
            PomodoroAutoAdvancer.PhaseBody(LocalizedTexts.Pomodoro("en"), PomodoroRunStatus.Active, PomodoroPhaseType.Break, 5, 1, 2, loop: true)
        ).IsEqualTo("Break — 5 min");
    }

    [Test]
    public async Task A_cycle_that_begins_with_a_break_says_so()
    {
        var body = PomodoroAutoAdvancer.PhaseBody(LocalizedTexts.Pomodoro("en"), PomodoroRunStatus.Active, PomodoroPhaseType.Break, 10, 3, 3, loop: true);

        await Assert.That(body).IsEqualTo("Cycle 1 done, the next one begins (a 10-minute break). Finished with the task?");
    }

    [Test]
    public async Task A_phase_left_at_zero_counts_up_to_its_timer_when_nobody_finishes()
    {
        var run = Start(autoAdvance: false); // its 25 minutes ran out at 9:25 and it was never touched again

        run.Finish(T0.AddHours(5), onItsOwn: true);

        await Assert.That(run.Phases[0].CompletedAt).IsEqualTo(T0.AddMinutes(25));
        await Assert.That(run.Phases[0].ActualSeconds).IsEqualTo(25 * 60);
        await Assert.That(run.CompletedAt).IsEqualTo(T0.AddHours(5));
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
        var next = T0.AddHours(5); // the 14:00 one

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
        var run = Start();

        var ends = run.ToResponse(T0.AddHours(5));
        await Assert.That(ends.FinishAt).IsEqualTo(T0.AddHours(5));
        await Assert.That(ends.EndsWithNextOccurrence).IsTrue();

        var later = run.ToResponse(T0.AddHours(13));
        await Assert.That(later.FinishAt).IsEqualTo(T0.AddHours(12));
        await Assert.That(later.EndsWithNextOccurrence).IsFalse();

        run.Finish(T0.AddMinutes(40));
        await Assert.That(run.ToResponse(T0.AddHours(5)).EndsWithNextOccurrence).IsFalse();
    }
}
