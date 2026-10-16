using Kadans.Modules.Tasks.Domain;
using Kadans.SharedKernel.Errors;

namespace Kadans.Tasks.Tests;

public class TodoOccurrenceTests
{
    private static readonly DateTimeOffset Now = new(2027, 1, 1, 12, 0, 0, TimeSpan.Zero);

    private static TodoOccurrence Pending() =>
        new() { TodoId = Guid.NewGuid(), OriginalScheduledAt = Now.AddDays(1), ScheduledAt = Now.AddDays(1) };

    [Test]
    public async Task Complete_then_complete_again_is_rejected()
    {
        var occurrence = Pending();

        await Assert.That(occurrence.Complete(Now).IsT1).IsTrue();
        await Assert.That(occurrence.Status).IsEqualTo(OccurrenceStatus.Completed);
        await Assert.That(occurrence.CompletedAt).IsEqualTo(Now);

        var again = occurrence.Complete(Now);
        await Assert.That(again.AsT0.ErrorType).IsEqualTo(ErrorTypes.TaskAlreadyCompleted);
    }

    [Test]
    public async Task Cancelled_occurrence_cannot_be_completed_or_rescheduled()
    {
        var occurrence = Pending();
        occurrence.Cancel("no longer needed", Now);

        await Assert.That(occurrence.Complete(Now).AsT0.ErrorType).IsEqualTo(ErrorTypes.TaskAlreadyCancelled);
        await Assert.That(occurrence.Reschedule(Now.AddDays(2), null, Now).AsT0.ErrorType).IsEqualTo(ErrorTypes.TaskAlreadyCancelled);
        await Assert.That(occurrence.CancellationReason).IsEqualTo("no longer needed");
    }

    [Test]
    public async Task Reschedule_keeps_the_original_instant_as_identity()
    {
        var occurrence = Pending();
        var original = occurrence.OriginalScheduledAt;

        var result = occurrence.Reschedule(Now.AddDays(3), "travel", Now);

        await Assert.That(result.IsT1).IsTrue();
        await Assert.That(occurrence.ScheduledAt).IsEqualTo(Now.AddDays(3));
        await Assert.That(occurrence.OriginalScheduledAt).IsEqualTo(original);
        await Assert.That(occurrence.IsRescheduled).IsTrue();
        await Assert.That(occurrence.IsUntouched).IsFalse();
        await Assert.That(occurrence.RescheduleReason).IsEqualTo("travel");
    }

    [Test]
    public async Task Reschedule_into_the_past_is_rejected()
    {
        var occurrence = Pending();

        var result = occurrence.Reschedule(Now.AddMinutes(-1), null, Now);

        await Assert.That(result.AsT0.ErrorType).IsEqualTo(ErrorTypes.InvalidDueDate);
        await Assert.That(occurrence.IsRescheduled).IsFalse();
    }

    [Test]
    public async Task Fresh_occurrence_is_untouched_until_the_user_acts_on_it()
    {
        var occurrence = Pending();
        await Assert.That(occurrence.IsUntouched).IsTrue();

        occurrence.SetNote("bring the folder");
        await Assert.That(occurrence.IsUntouched).IsFalse();
    }

    [Test]
    public async Task A_note_is_kept_trimmed_on_any_status_and_blank_removes_it()
    {
        var occurrence = Pending();
        occurrence.Complete(Now);

        await Assert.That(occurrence.SetNote("  felt great, ran 5 km \n").IsT1).IsTrue(); // how it went, once done
        await Assert.That(occurrence.Remarks).IsEqualTo("felt great, ran 5 km");

        occurrence.SetNote("   ");
        await Assert.That(occurrence.Remarks).IsNull();
    }

    [Test]
    public async Task A_note_is_at_most_4000_characters()
    {
        var occurrence = Pending();

        await Assert.That(occurrence.SetNote(new string('a', TodoOccurrence.MaxNoteLength)).IsT1).IsTrue();
        var tooLong = occurrence.SetNote(new string('b', TodoOccurrence.MaxNoteLength + 1));

        await Assert.That(tooLong.AsT0.ErrorType).IsEqualTo(ErrorTypes.ValidationError);
        await Assert.That(occurrence.Remarks!.Length).IsEqualTo(TodoOccurrence.MaxNoteLength); // the last one stays
    }
}
