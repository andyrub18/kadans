using System.Globalization;

namespace Kadans.Modules.Tasks.Features.CalendarFeed;

/// <summary>
/// One time of a todo as the feed reads it: a stored occurrence (pending or done) or, past the stored ones, a time its
/// rule gives. <paramref name="OriginalAt"/> is the time the rule gave, which names it even once moved to
/// <paramref name="At"/>. <paramref name="FocusMinutes"/>: its focus cycle's length, when the todo has one.
/// </summary>
internal sealed record FeedOccurrence(
    Guid TodoId,
    string Title,
    DateTimeOffset OriginalAt,
    DateTimeOffset At,
    bool Done,
    int? FocusMinutes,
    DateTimeOffset ChangedAt
);

/// <summary>
/// Turns a todo's times into calendar events. A todo has a start and no end, and a calendar needs one: the event lasts
/// its focus cycle when the todo has one, <see cref="DefaultMinutes"/> otherwise, and never runs into the todo's next
/// time (every hour, every five minutes). Pure: no clock, no database.
/// </summary>
internal static class CalendarFeedEvents
{
    public const int DefaultMinutes = 30;

    /// <summary>A reminder every five minutes is the closest two times of a todo come; an event is never shorter.</summary>
    public const int MinMinutes = 5;

    /// <summary>A day at most, whatever the cycle: an event over several days would bar the calendar's top.</summary>
    public const int MaxMinutes = 24 * 60;

    /// <summary>What a done time starts with, so a calendar shows it done without opening it.</summary>
    public const string DoneMark = "✓ ";

    /// <summary>
    /// The events, soonest first. Over <paramref name="max"/>, the ones from <paramref name="now"/> on are kept first
    /// (what a calendar is opened for), then the latest past ones.
    /// </summary>
    public static List<FeedEvent> Build(IEnumerable<FeedOccurrence> occurrences, DateTimeOffset now, int max)
    {
        var events = new List<(FeedEvent Event, DateTimeOffset At)>();
        foreach (var todo in occurrences.GroupBy(o => o.TodoId))
        {
            // The same time twice (a stored row and a computed one would be a bug upstream): once.
            var times = todo.DistinctBy(o => o.OriginalAt).OrderBy(o => o.At).ToList();
            for (var i = 0; i < times.Count; i++)
            {
                var time = times[i];
                var end = time.At.AddMinutes(Math.Clamp(time.FocusMinutes ?? DefaultMinutes, MinMinutes, MaxMinutes));
                // Up to the todo's next time, when that comes first.
                var next = times.Skip(i + 1).FirstOrDefault(other => other.At > time.At);
                if (next is not null && next.At < end)
                    end = Later(next.At, time.At.AddMinutes(MinMinutes));

                events.Add((new FeedEvent(Uid(time), time.Done ? DoneMark + time.Title : time.Title, time.At, end, time.ChangedAt), time.At));
            }
        }

        if (events.Count > max)
        {
            var ahead = events.Where(e => e.At >= now).OrderBy(e => e.At).Take(max).ToList();
            var past = events.Where(e => e.At < now).OrderByDescending(e => e.At).Take(max - ahead.Count);
            events = [.. ahead, .. past];
        }

        // A fixed order, whatever order the database answered in: the same events write the same file.
        return [.. events.OrderBy(e => e.At).ThenBy(e => e.Event.Uid, StringComparer.Ordinal).Select(e => e.Event)];
    }

    /// <summary>The todo and the time its rule gave: the same at every read, moved or not, stored or computed.</summary>
    internal static string Uid(FeedOccurrence occurrence) =>
        $"{occurrence.TodoId:N}-{occurrence.OriginalAt.UtcDateTime.ToString("yyyyMMdd'T'HHmmss'Z'", CultureInfo.InvariantCulture)}@kadansplanning.com";

    private static DateTimeOffset Later(DateTimeOffset a, DateTimeOffset b) => a > b ? a : b;
}
