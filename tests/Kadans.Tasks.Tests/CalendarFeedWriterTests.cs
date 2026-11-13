using System.Text;
using Ical.Net;
using Kadans.Modules.Tasks.Features.CalendarFeed;

namespace Kadans.Tasks.Tests;

/// <summary>
/// The feed's file is written by hand: these read it back with Ical.Net, the way a calendar app would, and check
/// what RFC 5545 asks of the bytes (line ends, folding, escaping).
/// </summary>
public class CalendarFeedWriterTests
{
    private static readonly DateTimeOffset Start = new(2027, 1, 4, 14, 0, 0, TimeSpan.FromHours(-5)); // 19:00 UTC

    private static FeedEvent Event(string summary, string uid = "one@kadansplanning.com") =>
        new(uid, summary, Start, Start.AddMinutes(30), new DateTimeOffset(2027, 1, 2, 8, 15, 0, TimeSpan.Zero));

    private static string Write(params FeedEvent[] events) => CalendarFeedWriter.Write(events, "Kadans", refreshMinutes: 15);

    [Test]
    public async Task A_calendar_app_reads_the_events_back()
    {
        var calendar = Calendar.Load(Write(Event("Write the report"), Event("Call the bank", "two@kadansplanning.com")))!;

        await Assert.That(calendar.Events.Count).IsEqualTo(2);
        var first = calendar.Events.Single(e => e.Uid == "one@kadansplanning.com");
        await Assert.That(first.Summary).IsEqualTo("Write the report");
        await Assert.That(first.DtStart!.AsUtc).IsEqualTo(new DateTime(2027, 1, 4, 19, 0, 0, DateTimeKind.Utc));
        await Assert.That(first.DtEnd!.AsUtc).IsEqualTo(new DateTime(2027, 1, 4, 19, 30, 0, DateTimeKind.Utc));
        await Assert.That(first.DtStamp!.AsUtc).IsEqualTo(new DateTime(2027, 1, 2, 8, 15, 0, DateTimeKind.Utc));
    }

    [Test]
    public async Task The_file_names_the_calendar_and_how_often_to_read_it_again()
    {
        var ics = Write();

        await Assert.That(ics).StartsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n");
        await Assert.That(ics).EndsWith("END:VCALENDAR\r\n");
        await Assert.That(ics).Contains("\r\nX-WR-CALNAME:Kadans\r\n");
        await Assert.That(ics).Contains("\r\nREFRESH-INTERVAL;VALUE=DURATION:PT15M\r\n");
        await Assert.That(ics).Contains("\r\nX-PUBLISHED-TTL:PT15M\r\n");
        await Assert.That(Calendar.Load(ics)!.Events.Count).IsEqualTo(0);
    }

    [Test]
    [Arguments("Rent; water, power \\ phone")]
    [Arguments("Réviser le français, payé à l'école")]
    [Arguments("Peye lekòl la; rele manman")]
    [Arguments("✓ Courir 🏃‍♀️ 5 km")]
    public async Task A_title_comes_back_as_it_was_written(string title)
    {
        var calendar = Calendar.Load(Write(Event(title)))!;

        await Assert.That(calendar.Events.Single().Summary).IsEqualTo(title);
    }

    [Test]
    public async Task A_title_cannot_add_a_line_to_the_file()
    {
        // A line break in a title would otherwise end SUMMARY and start a property of the writer's choosing.
        var ics = Write(Event("Dentist\r\nATTENDEE:mailto:someone@example.com\nEND:VEVENT\tx\u0000y"));

        var calendar = Calendar.Load(ics)!;
        await Assert.That(calendar.Events.Count).IsEqualTo(1);
        await Assert.That(calendar.Events.Single().Attendees.Count).IsEqualTo(0);
        await Assert.That(calendar.Events.Single().Summary).IsEqualTo("Dentist\nATTENDEE:mailto:someone@example.com\nEND:VEVENT xy");
        await Assert.That(ics.Split("\r\n").Count(line => line == "END:VEVENT")).IsEqualTo(1);
    }

    [Test]
    public async Task No_line_is_longer_than_75_octets_and_no_character_is_cut()
    {
        // 500 characters is the longest title; accents and emoji take two to four octets each.
        var title = string.Concat(Enumerable.Repeat("Préparer la réunion d'équipe 🗓️ à Pétion-Ville — ", 10));

        var ics = Write(Event(title));

        foreach (var line in ics.Split("\r\n"))
            await Assert.That(Encoding.UTF8.GetByteCount(line)).IsLessThanOrEqualTo(75);
        await Assert.That(ics).DoesNotContain("�");
        await Assert.That(Calendar.Load(ics)!.Events.Single().Summary).IsEqualTo(title);
    }

    [Test]
    public async Task Every_line_ends_with_cr_lf()
    {
        var ics = Write(Event("Write the report"));

        await Assert.That(ics.Replace("\r\n", "")).DoesNotContain("\n");
        await Assert.That(ics.Replace("\r\n", "")).DoesNotContain("\r");
    }

    [Test]
    public async Task The_same_events_write_the_same_file()
    {
        // The file's hash is its ETag: a calendar asking again must be told "unchanged".
        await Assert.That(Write(Event("Write the report"))).IsEqualTo(Write(Event("Write the report")));
    }
}
