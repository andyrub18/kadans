using Kadans.Modules.Tasks.Domain;
using Kadans.Modules.Tasks.Features.Todos.Occurrences;

namespace Kadans.Tasks.Tests;

/// <summary>Which occurrences the nightly cleanup removes: those nobody acted on, once they are old.</summary>
public class TasksRetentionTests
{
    private static readonly DateTimeOffset Now = new(2027, 6, 1, 7, 30, 0, TimeSpan.Zero);
    private static readonly Func<TodoOccurrence, bool> Disposable = TasksRetentionJob.Disposable(Now.AddDays(-90)).Compile();

    private static TodoOccurrence At(DateTimeOffset when) =>
        new() { TodoId = Guid.NewGuid(), OriginalScheduledAt = when, ScheduledAt = when };

    [Test]
    public async Task An_old_occurrence_nobody_acted_on_goes()
    {
        await Assert.That(Disposable(At(Now.AddDays(-91)))).IsTrue();
        await Assert.That(Disposable(At(Now.AddDays(-89)))).IsFalse();
        await Assert.That(Disposable(At(Now.AddDays(3)))).IsFalse();
    }

    [Test]
    public async Task What_someone_acted_on_stays_as_history()
    {
        var done = At(Now.AddDays(-200));
        done.Complete(Now.AddDays(-200));
        var skipped = At(Now.AddDays(-200));
        skipped.Cancel("holiday", Now.AddDays(-201));
        var annotated = At(Now.AddDays(-200));
        annotated.Remarks = "felt great";
        var moved = At(Now.AddDays(-300));
        moved.Reschedule(Now.AddDays(-200), "later", Now.AddDays(-301));

        foreach (var kept in new[] { done, skipped, annotated, moved })
            await Assert.That(Disposable(kept)).IsFalse();
    }
}
