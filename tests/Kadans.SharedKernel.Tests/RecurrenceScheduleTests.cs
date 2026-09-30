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
    public async Task Start_and_exceptions_are_normalized_to_utc()
    {
        var offsetStart = new DateTimeOffset(2027, 1, 10, 9, 0, 0, TimeSpan.FromHours(-5));
        var offsetException = new DateTimeOffset(2027, 1, 11, 9, 0, 0, TimeSpan.FromHours(-5));

        var schedule = Build(new RecurrenceSpec(Frequency.Daily), offsetStart, NewYork, [offsetException]);

        await Assert.That(schedule.Start.Offset).IsEqualTo(TimeSpan.Zero);
        await Assert.That(schedule.Exceptions.Single().Offset).IsEqualTo(TimeSpan.Zero);
        await Assert.That(schedule.GetOccurrences(Utc(2027, 1, 10), Utc(2027, 1, 13)))
            .IsEquivalentTo([Utc(2027, 1, 10, 14), Utc(2027, 1, 12, 14)]);
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
        await Assert.That(schedule.GetNextOccurrence(Utc(2027, 1, 3, 9))).IsNull();
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
    public async Task Indefinite_rule_always_has_a_next_occurrence()
    {
        var schedule = Build(new RecurrenceSpec(Frequency.Hourly), Utc(2027, 1, 1));

        await Assert.That(schedule.IsIndefinite).IsTrue();
        await Assert.That(schedule.GetNextOccurrence(Utc(2027, 2, 1))).IsEqualTo(Utc(2027, 2, 1, 1));
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
    public async Task An_hourly_rule_can_end_at_a_precise_moment_of_the_day()
    {
        // "Every 2 hours until 13:00": the client sends the picked end time, not the end of the day.
        var inclusive = Build(new RecurrenceSpec(Frequency.Hourly, Interval: 2, Until: Utc(2027, 1, 1, 13)), Utc(2027, 1, 1, 9));
        var justBefore = Build(new RecurrenceSpec(Frequency.Hourly, Interval: 2, Until: Utc(2027, 1, 1, 12, 59)), Utc(2027, 1, 1, 9));

        await Assert.That(inclusive.GetOccurrences(Utc(2027, 1, 1), Utc(2027, 1, 2)).Count).IsEqualTo(3); // 09, 11, 13
        await Assert.That(justBefore.GetOccurrences(Utc(2027, 1, 1), Utc(2027, 1, 2)).Count).IsEqualTo(2); // 09, 11
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

    // Walked in local time, Ical.Net 5.2.3 never returns from an hourly or minute rule that crosses an autumn DST
    // change. A regression would hang rather than fail, so the expansion runs against a clock.
    private static async Task<IReadOnlyList<DateTimeOffset>> Expanded(RecurrenceSchedule schedule, DateTimeOffset from, DateTimeOffset to)
    {
        var expansion = Task.Run(() => schedule.GetOccurrences(from, to));
        if (await Task.WhenAny(expansion, Task.Delay(TimeSpan.FromSeconds(10))) != expansion)
            throw new TimeoutException($"{schedule.Rrule} in {schedule.TimeZoneId} did not return: the autumn DST hang is back.");
        return await expansion;
    }

    [Test]
    [Arguments("America/Port-au-Prince", 11, 1)] // 02:00 EDT falls back to 01:00 EST (06:00Z)
    [Arguments("Europe/Paris", 10, 25)] // 03:00 CEST falls back to 02:00 CET (01:00Z)
    public async Task Hourly_rule_crosses_the_autumn_change_one_hour_apart(string zone, int month, int day)
    {
        var start = Utc(2026, month, day).AddHours(-12);
        var schedule = Build(new RecurrenceSpec(Frequency.Hourly), start, zone);

        var occurrences = await Expanded(schedule, start, start.AddHours(36));

        await Assert.That(occurrences.Count).IsEqualTo(37);
        await Assert.That(occurrences.Zip(occurrences.Skip(1), (a, b) => b - a).Distinct()).IsEquivalentTo([TimeSpan.FromHours(1)]);
    }

    [Test]
    public async Task Minute_rule_crosses_the_autumn_change()
    {
        var start = Utc(2026, 10, 31, 12);
        var schedule = Build(new RecurrenceSpec(Frequency.Minutely, Interval: 30), start, "America/Port-au-Prince");

        var occurrences = await Expanded(schedule, start, start.AddDays(2));

        await Assert.That(occurrences.Count).IsEqualTo(97);
    }

    [Test]
    public async Task Hourly_rule_counts_elapsed_hours_across_the_spring_change()
    {
        // US DST starts 2027-03-14 at 02:00 (07:00Z): local 01:00 is followed by 03:00, one hour later.
        var start = Utc(2027, 3, 14, 4);
        var schedule = Build(new RecurrenceSpec(Frequency.Hourly), start, NewYork);

        var occurrences = await Expanded(schedule, start, start.AddHours(6));

        await Assert.That(occurrences).IsEquivalentTo([.. Enumerable.Range(0, 7).Select(h => start.AddHours(h))]);
    }

    [Test]
    [Arguments(5)] // 01:30 EDT, the first 01:30 of the night
    [Arguments(6)] // 01:30 EST, the second: local time alone names the first, which used to lose this one
    public async Task One_time_rule_in_the_repeated_hour_fires_at_its_own_instant(int utcHour)
    {
        var at = Utc(2026, 11, 1, utcHour, 30);
        var schedule = RecurrenceSchedule.OneTime(at, "America/Port-au-Prince").AsT1;

        await Assert.That(schedule.GetOccurrences(Utc(2026, 10, 31), Utc(2026, 11, 2))).IsEquivalentTo([at]);
    }

    [Test]
    public async Task A_new_rule_repeats_at_most_5000_times()
    {
        var atLimit = RecurrenceSchedule.Create(new RecurrenceSpec(Frequency.Daily, Count: RecurrenceSchedule.MaxCount), Utc(2027, 1, 1));
        var beyond = RecurrenceSchedule.Create(new RecurrenceSpec(Frequency.Daily, Count: RecurrenceSchedule.MaxCount + 1), Utc(2027, 1, 1));

        await Assert.That(atLimit.IsT1).IsTrue();
        await Assert.That(beyond.AsT0.ErrorType).IsEqualTo(ErrorTypes.InvalidRecurrenceRule);
    }

    [Test]
    public async Task A_new_rule_ends_within_ten_years()
    {
        var start = Utc(2027, 1, 1, 9);
        var atLimit = RecurrenceSchedule.Create(new RecurrenceSpec(Frequency.Daily, Until: start.AddYears(10)), start);
        var beyond = RecurrenceSchedule.Create(new RecurrenceSpec(Frequency.Daily, Until: start.AddYears(10).AddDays(1)), start);

        await Assert.That(atLimit.IsT1).IsTrue();
        await Assert.That(beyond.AsT0.ErrorType).IsEqualTo(ErrorTypes.InvalidRecurrenceRule);
    }

    [Test]
    public async Task A_new_rule_fires_at_most_every_five_minutes()
    {
        var start = Utc(2027, 1, 1);
        List<int> allHours = [.. Enumerable.Range(0, 24)];
        var everyFour = RecurrenceSchedule.Create(new RecurrenceSpec(Frequency.Minutely, Interval: 4), start);
        var everyFive = RecurrenceSchedule.Create(new RecurrenceSpec(Frequency.Minutely, Interval: 5), start);
        // "N times a day" is hours × minutes: 24 × 12 is every 5 minutes, 24 × 13 is more.
        var daily288 = RecurrenceSchedule.Create(new RecurrenceSpec(Frequency.Daily, ByHour: allHours, ByMinute: [.. Enumerable.Range(0, 12).Select(i => i * 5)]), start);
        var daily312 = RecurrenceSchedule.Create(new RecurrenceSpec(Frequency.Daily, ByHour: allHours, ByMinute: [.. Enumerable.Range(0, 13)]), start);

        await Assert.That(everyFour.AsT0.ErrorType).IsEqualTo(ErrorTypes.InvalidInterval);
        await Assert.That(everyFive.IsT1).IsTrue();
        await Assert.That(daily288.IsT1).IsTrue();
        await Assert.That(daily312.AsT0.ErrorType).IsEqualTo(ErrorTypes.InvalidRecurrenceRule);
    }

    [Test]
    public async Task Hourly_and_minute_rules_take_no_hour_day_or_month_parts()
    {
        // Expanded in UTC, such parts would mean UTC hours and days, not the user's.
        var byHour = RecurrenceSchedule.Create(new RecurrenceSpec(Frequency.Hourly, ByHour: [9, 10]), Utc(2027, 1, 1));
        var byDay = RecurrenceSchedule.Create(new RecurrenceSpec(Frequency.Minutely, Interval: 15, ByDay: [DayOfWeek.Monday]), Utc(2027, 1, 1));

        await Assert.That(byHour.AsT0.ErrorType).IsEqualTo(ErrorTypes.InvalidRecurrenceRule);
        await Assert.That(byDay.AsT0.ErrorType).IsEqualTo(ErrorTypes.InvalidRecurrenceRule);
    }

    [Test]
    public async Task Expansion_stops_at_the_limit_whatever_the_window()
    {
        var schedule = Build(new RecurrenceSpec(Frequency.Minutely, Interval: 5), Utc(2027, 1, 1));

        var asked = schedule.GetOccurrences(Utc(2027, 1, 1), Utc(2127, 1, 1), limit: 10);
        var ceiling = schedule.GetOccurrences(Utc(2027, 1, 1), Utc(2127, 1, 1), limit: int.MaxValue);

        await Assert.That(asked.Count).IsEqualTo(10);
        await Assert.That(ceiling.Count).IsEqualTo(RecurrenceSchedule.MaxOccurrences);
    }
}
