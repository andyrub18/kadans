using Kadans.Modules.Tasks.Features.CalendarFeed;

namespace Kadans.Tasks.Tests;

/// <summary>A todo's times as calendar events (ARCHITECTURE → "The calendar feed"): how long each lasts, what names it, which are kept.</summary>
public class CalendarFeedEventsTests
{
    private static readonly DateTimeOffset Now = new(2027, 1, 4, 12, 0, 0, TimeSpan.Zero);
    private static readonly Guid Report = Guid.Parse("11111111-1111-1111-1111-111111111111");
    private static readonly Guid Pills = Guid.Parse("22222222-2222-2222-2222-222222222222");

    private static FeedOccurrence Time(
        Guid todo,
        DateTimeOffset at,
        string title = "Write the report",
        bool done = false,
        int? focusMinutes = null,
        DateTimeOffset? originalAt = null
    ) => new(todo, title, originalAt ?? at, at, done, focusMinutes, Now.AddDays(-1));

    private static string Minutes(FeedEvent e) => $"{(e.End - e.Start).TotalMinutes}";

    [Test]
    public async Task A_todo_without_a_focus_cycle_lasts_half_an_hour()
    {
        var events = CalendarFeedEvents.Build([Time(Report, Now.AddHours(2))], Now, max: 100);

        await Assert.That(events.Count).IsEqualTo(1);
        await Assert.That(events[0].Start).IsEqualTo(Now.AddHours(2));
        await Assert.That(Minutes(events[0])).IsEqualTo("30");
        await Assert.That(events[0].Summary).IsEqualTo("Write the report");
    }

    [Test]
    public async Task A_focus_todo_lasts_its_cycle()
    {
        // Four rounds of 25 + 5.
        var events = CalendarFeedEvents.Build([Time(Report, Now.AddHours(2), focusMinutes: 120)], Now, max: 100);

        await Assert.That(Minutes(events[0])).IsEqualTo("120");
    }

    [Test]
    public async Task An_event_is_never_longer_than_a_day_nor_shorter_than_five_minutes()
    {
        var events = CalendarFeedEvents.Build(
            [Time(Report, Now.AddHours(2), focusMinutes: 96 * 60), Time(Pills, Now.AddHours(3), focusMinutes: 1)],
            Now,
            max: 100
        );

        await Assert.That(Minutes(events[0])).IsEqualTo($"{24 * 60}");
        await Assert.That(Minutes(events[1])).IsEqualTo("5");
    }

    [Test]
    public async Task An_event_stops_where_the_todos_next_time_starts()
    {
        // Every ten minutes: three events of ten minutes, not three half hours over one another. The last has no next.
        var every10 = Enumerable.Range(0, 3).Select(i => Time(Pills, Now.AddMinutes(10 * i), "Drink water"));

        var events = CalendarFeedEvents.Build(every10, Now, max: 100);

        await Assert.That(string.Join(" ", events.Select(Minutes))).IsEqualTo("10 10 30");
    }

    [Test]
    public async Task Another_todos_time_does_not_shorten_an_event()
    {
        var events = CalendarFeedEvents.Build([Time(Report, Now.AddHours(1)), Time(Pills, Now.AddHours(1).AddMinutes(10))], Now, max: 100);

        await Assert.That(string.Join(" ", events.Select(Minutes))).IsEqualTo("30 30");
    }

    [Test]
    public async Task Two_times_of_a_todo_moved_onto_each_other_still_last_five_minutes()
    {
        var first = Time(Pills, Now.AddHours(1));
        var movedNextToIt = Time(Pills, Now.AddHours(1).AddMinutes(2), originalAt: Now.AddHours(5));

        var events = CalendarFeedEvents.Build([first, movedNextToIt], Now, max: 100);

        await Assert.That(Minutes(events[0])).IsEqualTo("5");
    }

    [Test]
    public async Task A_done_time_says_so_in_its_title()
    {
        var events = CalendarFeedEvents.Build([Time(Report, Now.AddHours(-3), done: true)], Now, max: 100);

        await Assert.That(events[0].Summary).IsEqualTo("✓ Write the report");
    }

    [Test]
    public async Task An_event_keeps_its_name_when_it_is_moved()
    {
        // The time the rule gave names it: a calendar app must see one event moved, not one gone and one new.
        var planned = Time(Report, Now.AddDays(1));
        var moved = Time(Report, Now.AddDays(1).AddHours(4), originalAt: Now.AddDays(1));

        var before = CalendarFeedEvents.Build([planned], Now, max: 100)[0];
        var after = CalendarFeedEvents.Build([moved], Now, max: 100)[0];

        await Assert.That(after.Uid).IsEqualTo(before.Uid);
        await Assert.That(after.Uid).IsEqualTo("11111111111111111111111111111111-20270105T120000Z@kadansplanning.com");
        await Assert.That(after.Start).IsEqualTo(Now.AddDays(1).AddHours(4));
    }

    [Test]
    public async Task The_same_time_of_a_todo_is_one_event()
    {
        var events = CalendarFeedEvents.Build([Time(Report, Now.AddDays(1)), Time(Report, Now.AddDays(1))], Now, max: 100);

        await Assert.That(events.Count).IsEqualTo(1);
    }

    [Test]
    public async Task Over_the_most_a_feed_holds_the_times_to_come_are_kept_first()
    {
        var past = Enumerable.Range(1, 5).Select(i => Time(Report, Now.AddDays(-i), originalAt: Now.AddDays(-i)));
        var ahead = Enumerable.Range(1, 3).Select(i => Time(Report, Now.AddDays(i), originalAt: Now.AddDays(i)));

        var events = CalendarFeedEvents.Build([.. past, .. ahead], Now, max: 5);

        // The three to come, then the two latest past ones; soonest first.
        await Assert.That(string.Join(" ", events.Select(e => (e.Start - Now).TotalDays))).IsEqualTo("-2 -1 1 2 3");
    }

    [Test]
    public async Task The_events_come_in_the_same_order_whatever_order_they_were_read_in()
    {
        FeedOccurrence[] times = [Time(Pills, Now.AddHours(1), "Pills"), Time(Report, Now.AddHours(1)), Time(Report, Now.AddHours(-1))];

        var one = CalendarFeedEvents.Build(times, Now, max: 100).Select(e => e.Uid).ToList();
        var other = CalendarFeedEvents.Build(times.Reverse(), Now, max: 100).Select(e => e.Uid).ToList();

        await Assert.That(other).IsEquivalentTo(one, TUnit.Assertions.Enums.CollectionOrdering.Matching);
    }
}
