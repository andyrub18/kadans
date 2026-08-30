using Kadans.SharedKernel.Errors;
using Kadans.SharedKernel.Recurrence;

namespace Kadans.SharedKernel.Tests;

public class RecurrenceScheduleTests
{
    private const string NewYork = "America/New_York";

    private static DateTimeOffset Utc(int y, int m, int d, int h = 0, int min = 0) =>
        new(y, m, d, h, min, 0, TimeSpan.Zero);

    private static RecurrenceSchedule Build(
        RecurrenceSpec spec,
        DateTimeOffset start,
        string? tz = null,
        IEnumerable<DateTimeOffset>? exceptions = null
    )
    {
        var result = RecurrenceSchedule.Create(spec, start, tz, exceptions);
        if (result.IsT0)
            throw new InvalidOperationException(result.AsT0.ErrorMessage);
        return result.AsT1;
    }

    [Test]
    public async Task Daily_rule_keeps_the_wall_clock_hour_of_its_time_zone()
    {
        // 09:00 in New York, sent by a client as an offset instant.
        var start = new DateTimeOffset(2027, 1, 10, 9, 0, 0, TimeSpan.FromHours(-5));
        var schedule = Build(new RecurrenceSpec(Frequency.Daily), start, NewYork);

        var occurrences = schedule.GetOccurrences(start, start.AddDays(2));

        await Assert.That(occurrences).IsEquivalentTo(
            [Utc(2027, 1, 10, 14), Utc(2027, 1, 11, 14), Utc(2027, 1, 12, 14)]
        );
    }

    [Test]
    public async Task Daily_rule_follows_daylight_saving_transitions()
    {
        // US DST starts 2027-03-14: 09:00 New York moves from 14:00Z to 13:00Z.
        var schedule = Build(new RecurrenceSpec(Frequency.Daily), Utc(2027, 3, 13, 14), NewYork);

        var occurrences = schedule.GetOccurrences(Utc(2027, 3, 13), Utc(2027, 3, 15));

        await Assert.That(occurrences).IsEquivalentTo([Utc(2027, 3, 13, 14), Utc(2027, 3, 14, 13)]);
    }

    [Test]
    public async Task Interval_is_anchored_on_the_start_date_not_on_the_query_date()
    {
        var schedule = Build(new RecurrenceSpec(Frequency.Daily, Interval: 2), Utc(2027, 1, 1, 9));

        var next = schedule.GetNextOccurrence(after: Utc(2027, 1, 2));

        await Assert.That(next).IsEqualTo(Utc(2027, 1, 3, 9));
    }

    [Test]
    public async Task Weekly_rule_every_other_monday()
    {
        var schedule = Build(
            new RecurrenceSpec(Frequency.Weekly, Interval: 2, ByDay: [DayOfWeek.Monday]),
            Utc(2027, 1, 4, 9) // a Monday
        );

        var occurrences = schedule.GetOccurrences(Utc(2027, 1, 1), Utc(2027, 2, 16));

        await Assert.That(occurrences).IsEquivalentTo(
            [Utc(2027, 1, 4, 9), Utc(2027, 1, 18, 9), Utc(2027, 2, 1, 9), Utc(2027, 2, 15, 9)]
        );
    }

    [Test]
    public async Task Monthly_last_friday_via_set_position()
    {
        var schedule = Build(
            new RecurrenceSpec(Frequency.Monthly, ByDay: [DayOfWeek.Friday], BySetPos: [-1], Count: 3),
            Utc(2027, 1, 1, 9)
        );

        var occurrences = schedule.GetOccurrences(Utc(2027, 1, 1), Utc(2028, 1, 1));

        await Assert.That(occurrences).IsEquivalentTo(
            [Utc(2027, 1, 29, 9), Utc(2027, 2, 26, 9), Utc(2027, 3, 26, 9)]
        );
    }

    [Test]
    public async Task Monthly_negative_month_day_means_end_of_month()
    {
        var schedule = Build(
            new RecurrenceSpec(Frequency.Monthly, ByMonthDay: [-1], Count: 3),
            Utc(2027, 1, 31, 9)
        );

        var occurrences = schedule.GetOccurrences(Utc(2027, 1, 1), Utc(2028, 1, 1));

        await Assert.That(occurrences).IsEquivalentTo(
            [Utc(2027, 1, 31, 9), Utc(2027, 2, 28, 9), Utc(2027, 3, 31, 9)]
        );
    }

    [Test]
    public async Task Count_bounds_the_rule_and_exceptions_remove_from_the_bounded_set()
    {
        var schedule = Build(
            new RecurrenceSpec(Frequency.Daily, Count: 3),
            Utc(2027, 1, 1, 9),
            exceptions: [Utc(2027, 1, 2, 9)]
        );

        var occurrences = schedule.GetOccurrences(Utc(2027, 1, 1), Utc(2027, 12, 31));

        await Assert.That(occurrences).IsEquivalentTo([Utc(2027, 1, 1, 9), Utc(2027, 1, 3, 9)]);
        await Assert.That(schedule.GetLastOccurrence()).IsEqualTo(Utc(2027, 1, 3, 9));
    }

    [Test]
    public async Task Until_bounds_the_rule_inclusively()
    {
        var schedule = Build(
            new RecurrenceSpec(Frequency.Daily, Until: Utc(2027, 1, 3, 9)),
            Utc(2027, 1, 1, 9)
        );

        var occurrences = schedule.GetOccurrences(Utc(2027, 1, 1), Utc(2027, 12, 31));

        await Assert.That(occurrences.Count).IsEqualTo(3);
        await Assert.That(schedule.Until).IsEqualTo(Utc(2027, 1, 3, 9));
        await Assert.That(schedule.IsIndefinite).IsFalse();
    }

    [Test]
    public async Task Window_lower_bound_is_inclusive()
    {
        var start = Utc(2027, 1, 1, 9);
        var schedule = Build(new RecurrenceSpec(Frequency.Daily), start);

        var occurrences = schedule.GetOccurrences(start, start);

        await Assert.That(occurrences).IsEquivalentTo([start]);
    }

    [Test]
    public async Task One_time_schedule_fires_once()
    {
        var at = Utc(2027, 6, 1, 15, 30);
        var schedule = RecurrenceSchedule.OneTime(at).AsT1;

        await Assert.That(schedule.IsOneTime).IsTrue();
        await Assert.That(schedule.GetOccurrences(Utc(2020, 1, 1), Utc(2030, 1, 1))).IsEquivalentTo([at]);
        await Assert.That(schedule.GetNextOccurrence(at)).IsNull();
    }

    [Test]
    public async Task Indefinite_rule_has_no_last_occurrence()
    {
        var schedule = Build(new RecurrenceSpec(Frequency.Hourly), Utc(2027, 1, 1));

        await Assert.That(schedule.IsIndefinite).IsTrue();
        await Assert.That(schedule.GetLastOccurrence()).IsNull();
    }

    [Test]
    public async Task Stored_rrule_rehydrates_to_the_same_schedule()
    {
        var original = Build(
            new RecurrenceSpec(Frequency.Weekly, Interval: 2, ByDay: [DayOfWeek.Monday, DayOfWeek.Thursday], ByHour: [8], ByMinute: [15], Count: 6),
            Utc(2027, 1, 4, 13, 15),
            NewYork
        );

        var rehydrated = RecurrenceSchedule.FromStored(
            original.Rrule,
            original.TimeZoneId,
            original.Start,
            original.Exceptions
        );

        await Assert.That(original.Rrule).IsEqualTo("FREQ=WEEKLY;INTERVAL=2;COUNT=6;BYDAY=MO,TH;BYHOUR=8;BYMINUTE=15");
        await Assert.That(rehydrated.GetOccurrences(Utc(2027, 1, 1), Utc(2028, 1, 1)))
            .IsEquivalentTo(original.GetOccurrences(Utc(2027, 1, 1), Utc(2028, 1, 1)));
    }

    [Test]
    [Arguments("Mars/Olympus_Mons")]
    [Arguments("")]
    public async Task Unknown_time_zone_is_rejected(string tz)
    {
        var result = RecurrenceSchedule.Create(new RecurrenceSpec(Frequency.Daily), Utc(2027, 1, 1), tz);

        await Assert.That(result.IsT0).IsTrue();
        await Assert.That(result.AsT0.ErrorType).IsEqualTo(ErrorTypes.InvalidTimeZone);
    }

    [Test]
    public async Task Count_and_until_are_mutually_exclusive()
    {
        var result = RecurrenceSchedule.Create(
            new RecurrenceSpec(Frequency.Daily, Count: 2, Until: Utc(2027, 2, 1)),
            Utc(2027, 1, 1)
        );

        await Assert.That(result.IsT0).IsTrue();
        await Assert.That(result.AsT0.ErrorType).IsEqualTo(ErrorTypes.InvalidRecurrenceRule);
    }

    [Test]
    public async Task Out_of_range_parts_are_rejected()
    {
        var hour = RecurrenceSchedule.Create(new RecurrenceSpec(Frequency.Daily, ByHour: [24]), Utc(2027, 1, 1));
        var monthDay = RecurrenceSchedule.Create(new RecurrenceSpec(Frequency.Monthly, ByMonthDay: [0]), Utc(2027, 1, 1));
        var setPosAlone = RecurrenceSchedule.Create(new RecurrenceSpec(Frequency.Monthly, BySetPos: [1]), Utc(2027, 1, 1));

        await Assert.That(hour.AsT0.ErrorType).IsEqualTo(ErrorTypes.InvalidHour);
        await Assert.That(monthDay.AsT0.ErrorType).IsEqualTo(ErrorTypes.InvalidDayOfMonth);
        await Assert.That(setPosAlone.AsT0.ErrorType).IsEqualTo(ErrorTypes.PossibleInvalidSetPos);
    }
}
