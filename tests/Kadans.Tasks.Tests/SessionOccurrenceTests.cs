using Kadans.Modules.Tasks.Features.Pomodoro;

namespace Kadans.Tasks.Tests;

/// <summary>Which occurrence a focus session is for, so its end can offer to mark it done.</summary>
public class SessionOccurrenceTests
{
    private static readonly DateTimeOffset Now = new(2027, 1, 4, 19, 50, 0, TimeSpan.Zero);
    private static readonly Guid Earlier = Guid.NewGuid();
    private static readonly Guid Later = Guid.NewGuid();

    [Test]
    public async Task A_session_is_for_the_occurrence_nearest_its_start()
    {
        // Ten minutes early for the 20:00, the 08:00 still pending: the 20:00.
        await Assert.That(SessionOccurrence.Nearest((Earlier, Now.AddHours(-11).AddMinutes(-50)), (Later, Now.AddMinutes(10)), Now)).IsEqualTo(Later);
        // Late for the 19:00, the next at 07:00: the 19:00.
        await Assert.That(SessionOccurrence.Nearest((Earlier, Now.AddMinutes(-50)), (Later, Now.AddHours(11).AddMinutes(10)), Now)).IsEqualTo(Earlier);
    }

    [Test]
    public async Task On_a_tie_the_one_ahead_and_none_when_none_is_near()
    {
        await Assert.That(SessionOccurrence.Nearest((Earlier, Now.AddHours(-1)), (Later, Now.AddHours(1)), Now)).IsEqualTo(Later);
        await Assert.That(SessionOccurrence.Nearest((Earlier, Now.AddHours(-3)), null, Now)).IsEqualTo(Earlier);
        await Assert.That(SessionOccurrence.Nearest(null, null, Now)).IsNull();
    }
}
