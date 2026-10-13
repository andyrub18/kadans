using Kadans.Modules.Tasks.Domain;
using Kadans.Modules.Tasks.Features.Reminders;

namespace Kadans.Tasks.Tests;

/// <summary>An occurrence rings before its start and at it ("starts now"), each once (ARCHITECTURE → "Reminders ring on the phone").</summary>
public class ReminderMomentsTests
{
    private static readonly DateTimeOffset Now = new(2027, 1, 4, 12, 0, 0, TimeSpan.Zero);

    /// <summary>An occurrence starting at <paramref name="start"/>, reminded <paramref name="leadMinutes"/> before (null: notifications off).</summary>
    private static TodoOccurrence At(DateTimeOffset start, int? leadMinutes)
    {
        var todo = new Todo("Work alarm", "", RecurrenceRule.CreateOneTimeRule(start).AsT1, notificationEnabled: leadMinutes is not null);
        if (leadMinutes is { } lead)
            todo.NotificationLeadTime = TimeSpan.FromMinutes(lead);
        var occurrence = new TodoOccurrence
        {
            TodoId = todo.Id,
            Todo = todo,
            OriginalScheduledAt = start,
            ScheduledAt = start,
        };
        occurrence.RefreshNotifyAt(todo);
        return occurrence;
    }

    private static string Clock(IEnumerable<DateTimeOffset> moments) => string.Join(" ", moments.Select(m => $"{m:HH:mm}"));

    [Test]
    public async Task An_occurrence_rings_before_its_start_and_at_it()
    {
        var start = Now.AddHours(1);

        await Assert.That(Clock(ReminderMoments.Of(start.AddMinutes(-15), start))).IsEqualTo("12:45 13:00");
        await Assert.That(Clock(ReminderMoments.Of(start, start))).IsEqualTo("13:00"); // "at the start": once
        await Assert.That(ReminderMoments.Of(null, start)).IsEmpty(); // notifications off
    }

    [Test]
    public async Task A_time_an_app_hands_back_is_the_same_moment_to_the_millisecond()
    {
        var start = Now.AddHours(1);
        var moments = ReminderMoments.Of(start.AddMinutes(-15), start);

        await Assert.That(ReminderMoments.Includes(moments, start.AddTicks(4_000))).IsTrue(); // 0.4 ms off: a rounding
        await Assert.That(ReminderMoments.Includes(moments, start.AddMinutes(-15))).IsTrue();
        await Assert.That(ReminderMoments.Includes(moments, start.AddMinutes(-10))).IsFalse(); // the lead changed since
        await Assert.That(ReminderMoments.Includes(moments, start.AddSeconds(1))).IsFalse();
    }

    [Test]
    public async Task A_window_holds_each_reminder_still_ahead_soonest_first()
    {
        var soon = At(Now.AddMinutes(10), 15); // 11:55, gone, and 12:10
        var later = At(Now.AddMinutes(20), 15); // 12:05 and 12:20
        var atStart = At(Now.AddMinutes(30), 0); // 12:30 alone

        // The query hands them over by notify time.
        var (reminders, through) = ReminderMoments.Window(
            [soon, later, atStart],
            o => o.NotifyAt,
            o => o.ScheduledAt,
            Now,
            Now.AddDays(7),
            cut: false,
            cap: 400
        );

        await Assert.That(Clock(reminders.Select(r => r.At))).IsEqualTo("12:05 12:10 12:20 12:30");
        await Assert.That(reminders.Select(r => r.Occurrence)).IsEquivalentTo([later, soon, later, atStart]);
        await Assert.That(through).IsEqualTo(Now.AddDays(7));
    }

    [Test]
    public async Task A_window_reaches_only_as_far_as_it_is_whole()
    {
        var first = At(Now.AddMinutes(20), 15); // 12:05, 12:20
        var second = At(Now.AddMinutes(40), 30); // 12:10, 12:40

        // The query was cut at the second: an occurrence it did not fetch may ring any time after 12:10.
        var (cut, cutThrough) = ReminderMoments.Window([first, second], o => o.NotifyAt, o => o.ScheduledAt, Now, Now.AddDays(7), cut: true, cap: 400);
        await Assert.That(Clock(cut.Select(r => r.At))).IsEqualTo("12:05 12:10");
        await Assert.That(cutThrough).IsEqualTo(Now.AddMinutes(10));

        // At the cap, it reaches its last reminder: the push covers what lies after it.
        var (capped, cappedThrough) = ReminderMoments.Window([first, second], o => o.NotifyAt, o => o.ScheduledAt, Now, Now.AddDays(7), cut: false, cap: 3);
        await Assert.That(Clock(capped.Select(r => r.At))).IsEqualTo("12:05 12:10 12:20");
        await Assert.That(cappedThrough).IsEqualTo(Now.AddMinutes(20));
    }

    [Test]
    public async Task The_reminder_before_the_start_then_starts_now_each_once()
    {
        var start = Now.AddMinutes(15);
        var occurrence = At(start, 15);

        await Assert.That(occurrence.ReminderMoment(Now)).IsEqualTo(Now);
        occurrence.MarkReminded(Now);
        await Assert.That(occurrence.NotifiedAt).IsEqualTo(Now);
        await Assert.That(occurrence.StartNotifiedAt).IsNull(); // "starts now" is still to come

        await Assert.That(occurrence.ReminderMoment(start)).IsEqualTo(start);
        occurrence.MarkReminded(start);
        await Assert.That(occurrence.NotifiedAt).IsEqualTo(Now);
        await Assert.That(occurrence.StartNotifiedAt).IsEqualTo(start);
    }

    [Test]
    public async Task One_sent_past_the_start_reads_starts_now_and_stands_for_both()
    {
        // The server was down at 11:43 and at 11:58, or the todo was made inside its lead and its start came first.
        var occurrence = At(Now.AddMinutes(-2), 15);

        await Assert.That(occurrence.ReminderMoment(Now)).IsEqualTo(Now.AddMinutes(-2));
        occurrence.MarkReminded(Now);
        await Assert.That(occurrence.NotifiedAt).IsEqualTo(Now);
        await Assert.That(occurrence.StartNotifiedAt).IsEqualTo(Now);
    }

    [Test]
    public async Task A_moved_occurrence_rings_both_again()
    {
        var occurrence = At(Now.AddMinutes(-1), 15);
        occurrence.MarkReminded(Now);

        occurrence.Reschedule(Now.AddHours(2), null, Now);

        await Assert.That(occurrence.NotifiedAt).IsNull();
        await Assert.That(occurrence.StartNotifiedAt).IsNull();
        await Assert.That(occurrence.NotifyAt).IsEqualTo(Now.AddHours(2).AddMinutes(-15));
    }

    [Test]
    public async Task Starts_now_has_the_start_as_its_identity_and_stays_of_use_ten_minutes()
    {
        var occurrence = At(Now, 15);
        var sentAt = Now.AddSeconds(3); // the job runs a few seconds after

        var message = ReminderNotification.Message(occurrence, null, sentAt, 7, occurrence.ReminderMoment(sentAt));

        await Assert.That(message.Body).IsEqualTo("Starts now (12:00)");
        await Assert.That(message.Data!["notifyAt"]).IsEqualTo(Now.ToString("O"));
        await Assert.That(message.Data["pomodoroTemplateId"]).IsEqualTo(""); // not a focus todo: its buttons say "Done"
        await Assert.That(message.Reminder!.NotifyAt).IsEqualTo(Now);
        await Assert.That(message.Reminder.UsefulUntil).IsEqualTo(Now.AddMinutes(10));
    }
}
