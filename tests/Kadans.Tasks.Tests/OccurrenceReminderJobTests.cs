using Kadans.Modules.Tasks.Features.Todos.Occurrences;

namespace Kadans.Tasks.Tests;

public class OccurrenceReminderJobTests
{
    [Test]
    [Arguments(0, 0, 30, "less than a minute")]
    [Arguments(0, 15, 0, "15 min")]
    [Arguments(2, 0, 0, "2 h")]
    [Arguments(2, 5, 0, "2 h 05 min")]
    [Arguments(72, 0, 0, "3 d")]
    [Arguments(76, 10, 0, "3 d 4 h")]
    public async Task Describe_renders_a_compact_duration(int hours, int minutes, int seconds, string expected)
    {
        await Assert.That(OccurrenceReminderJob.Describe(new TimeSpan(hours, minutes, seconds))).IsEqualTo(expected);
    }
}
