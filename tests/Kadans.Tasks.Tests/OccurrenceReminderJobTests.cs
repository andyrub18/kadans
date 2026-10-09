using Kadans.Modules.Tasks.Features.Reminders;
using Kadans.Modules.Tasks.Features;
using Kadans.Modules.Tasks.Features.Todos.Occurrences;

namespace Kadans.Tasks.Tests;

public class OccurrenceReminderJobTests
{
    [Test]
    [Arguments(0, 15, 0, "15 min")]
    [Arguments(2, 0, 0, "2 h")]
    [Arguments(2, 5, 0, "2 h 05 min")]
    [Arguments(72, 0, 0, "3 d")]
    [Arguments(76, 10, 0, "3 d 4 h")]
    public async Task Describe_renders_a_compact_duration(int hours, int minutes, int seconds, string expected)
    {
        var texts = LocalizedTexts.Reminder("en");
        await Assert.That(ReminderNotification.Describe(new TimeSpan(hours, minutes, seconds), texts)).IsEqualTo(expected);
    }

    // The job runs a few seconds after the notify time, so a reminder is always a little short of its lead.
    [Test]
    [Arguments(0, 14, 51, "15 min")]
    [Arguments(0, 59, 52, "1 h")]
    [Arguments(1, 59, 55, "2 h")]
    [Arguments(2, 4, 50, "2 h 05 min")]
    [Arguments(23, 59, 58, "1 d")]
    [Arguments(0, 0, 45, "1 min")]
    public async Task Describe_rounds_up_so_a_reminder_reads_as_its_lead(int hours, int minutes, int seconds, string expected)
    {
        var texts = LocalizedTexts.Reminder("en");
        await Assert.That(ReminderNotification.Describe(new TimeSpan(hours, minutes, seconds), texts)).IsEqualTo(expected);
    }

    [Test]
    [Arguments("fr", "15 min", "1 j 2 h")]
    [Arguments("ht", "15 min", "1 jou 2 è")]
    public async Task Describe_uses_the_users_unit_words(string language, string quarter, string dayAndTwoHours)
    {
        var texts = LocalizedTexts.Reminder(language);
        await Assert.That(ReminderNotification.Describe(new TimeSpan(0, 14, 55), texts)).IsEqualTo(quarter);
        await Assert.That(ReminderNotification.Describe(new TimeSpan(1, 1, 59, 50), texts)).IsEqualTo(dayAndTwoHours);
    }
}
