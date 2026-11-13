using System.Globalization;
using System.Text;

namespace Kadans.Modules.Tasks.Features.CalendarFeed;

/// <summary>One event of the feed: a time of a todo, from <paramref name="Start"/> to <paramref name="End"/>.</summary>
/// <param name="Uid">The same for the same time of the same todo at every read: a calendar app tells events apart by it.</param>
/// <param name="Stamp">When what the event says last changed (never "now": the same content must write the same file).</param>
internal sealed record FeedEvent(string Uid, string Summary, DateTimeOffset Start, DateTimeOffset End, DateTimeOffset Stamp);

/// <summary>
/// Writes the feed as an iCalendar file (RFC 5545), by hand: a handful of fixed properties, which a general serializer
/// builds an object graph for at every read. What the format asks for is here: CRLF line ends, text escaped, lines
/// folded at 75 octets without cutting a character, times in UTC. The tests read the result back with Ical.Net.
/// The same events always write the same bytes: the feed's ETag is their hash.
/// </summary>
internal static class CalendarFeedWriter
{
    public const string ContentType = "text/calendar; charset=utf-8";

    private const int MaxLineOctets = 75;

    /// <param name="refreshMinutes">How often a calendar app is invited to read again (Apple's and Outlook's listen; Google's does not).</param>
    public static string Write(IReadOnlyList<FeedEvent> events, string calendarName, int refreshMinutes)
    {
        var refresh = $"PT{Math.Max(1, refreshMinutes)}M";
        var ics = new StringBuilder(256 + events.Count * 192);
        Line(ics, "BEGIN:VCALENDAR");
        Line(ics, "VERSION:2.0");
        Line(ics, "PRODID:-//Kadans//Calendar feed//EN");
        Line(ics, "CALSCALE:GREGORIAN");
        Line(ics, "METHOD:PUBLISH");
        Line(ics, "NAME:" + Text(calendarName));
        Line(ics, "X-WR-CALNAME:" + Text(calendarName));
        Line(ics, "REFRESH-INTERVAL;VALUE=DURATION:" + refresh);
        Line(ics, "X-PUBLISHED-TTL:" + refresh);
        foreach (var e in events)
        {
            Line(ics, "BEGIN:VEVENT");
            Line(ics, "UID:" + Text(e.Uid));
            Line(ics, "DTSTAMP:" + Utc(e.Stamp));
            Line(ics, "DTSTART:" + Utc(e.Start));
            Line(ics, "DTEND:" + Utc(e.End));
            Line(ics, "SUMMARY:" + Text(e.Summary));
            Line(ics, "END:VEVENT");
        }
        Line(ics, "END:VCALENDAR");
        return ics.ToString();
    }

    private static string Utc(DateTimeOffset instant) => instant.UtcDateTime.ToString("yyyyMMdd'T'HHmmss'Z'", CultureInfo.InvariantCulture);

    /// <summary>
    /// A TEXT value (RFC 5545 §3.3.11): backslash, semicolon and comma escaped, a line break written <c>\n</c>, every
    /// other control character dropped (a title pasted from elsewhere may hold any).
    /// </summary>
    internal static string Text(string value)
    {
        var text = new StringBuilder(value.Length + 8);
        foreach (var c in value)
        {
            switch (c)
            {
                case '\\':
                    text.Append(@"\\");
                    break;
                case ';':
                    text.Append(@"\;");
                    break;
                case ',':
                    text.Append(@"\,");
                    break;
                case '\n':
                    text.Append(@"\n");
                    break;
                case '\t':
                    text.Append(' ');
                    break;
                default:
                    if (!char.IsControl(c))
                        text.Append(c);
                    break;
            }
        }
        return text.ToString();
    }

    /// <summary>
    /// One content line, folded (§3.1): no more than 75 octets a line, the next ones starting with a space that counts
    /// among theirs. Cut between characters only, an emoji's two halves kept together.
    /// </summary>
    private static void Line(StringBuilder ics, string line)
    {
        // Most lines: short and plain, one octet a character.
        if (line.Length <= MaxLineOctets && Ascii.IsValid(line))
        {
            ics.Append(line).Append("\r\n");
            return;
        }

        var octets = 0;
        Span<char> utf16 = stackalloc char[2];
        foreach (var rune in line.EnumerateRunes())
        {
            var size = rune.Utf8SequenceLength;
            if (octets + size > MaxLineOctets)
            {
                ics.Append("\r\n ");
                octets = 1;
            }
            ics.Append(utf16[..rune.EncodeToUtf16(utf16)]);
            octets += size;
        }
        ics.Append("\r\n");
    }
}
