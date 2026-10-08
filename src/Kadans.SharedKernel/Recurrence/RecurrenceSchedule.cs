using Ical.Net;
using Ical.Net.CalendarComponents;
using Ical.Net.DataTypes;
using Ical.Net.Serialization.DataTypes;
using Kadans.SharedKernel.Errors;
using OneOf;

namespace Kadans.SharedKernel.Recurrence;

/// <summary>
/// An RFC 5545 recurrence: an RRULE string, an IANA time zone that gives the rule its
/// wall-clock meaning (so "every day at 09:00" survives DST), a start instant and a set of
/// excluded instants. Expansion is delegated to Ical.Net; all instants in and out are
/// <see cref="DateTimeOffset"/> (UTC).
/// </summary>
public sealed class RecurrenceSchedule
{
    public const string DefaultTimeZoneId = "UTC";

    /// <summary>The most instants one <see cref="GetOccurrences"/> call returns; callers ask for what they keep.</summary>
    public const int MaxOccurrences = 10_000;

    // What a new rule may describe (stored rules are trusted as they are). The densest rule, every 5 minutes,
    // fills a 30-day occurrence horizon with 8,640 instants: under MaxOccurrences.
    public const int MaxCount = 5_000;
    public const int MaxYears = 10;
    public const int MinMinuteInterval = 5;
    public const int MaxPerDay = 24 * 60 / MinMinuteInterval;

    private readonly RecurrencePattern pattern;
    private readonly HashSet<DateTimeOffset> exceptions;

    public string Rrule { get; }
    public string TimeZoneId { get; }
    public DateTimeOffset Start { get; }
    public IReadOnlyCollection<DateTimeOffset> Exceptions => exceptions;

    public Frequency Frequency => FromFrequencyType(pattern.Frequency);
    public int Interval => pattern.Interval;
    public int? Count => pattern.Count;
    public DateTimeOffset? Until =>
        pattern.Until is null ? null : new DateTimeOffset(pattern.Until.AsUtc, TimeSpan.Zero);
    public bool IsOneTime => Count == 1;
    public bool IsIndefinite => Count is null && pattern.Until is null;

    private RecurrenceSchedule(
        RecurrencePattern pattern,
        string timeZoneId,
        DateTimeOffset start,
        IEnumerable<DateTimeOffset> exceptions
    )
    {
        this.pattern = pattern;
        this.exceptions = [.. exceptions.Select(WholeSeconds)];
        Rrule = new RecurrenceRuleSerializer().SerializeToString(pattern)
            ?? throw new InvalidOperationException("Could not serialize recurrence pattern.");
        TimeZoneId = timeZoneId;
        Start = WholeSeconds(start);
    }

    /// <summary>
    /// iCalendar times have no fractions of a second, and Ical.Net drops them: a start at 09:00:00.250 expands to
    /// 09:00:00, before the start itself, and a one-time rule would have no occurrence at all.
    /// </summary>
    private static DateTimeOffset WholeSeconds(DateTimeOffset instant)
    {
        var utc = instant.ToUniversalTime();
        return utc.AddTicks(-(utc.Ticks % TimeSpan.TicksPerSecond));
    }

    public static OneOf<ApplicationError, RecurrenceSchedule> Create(
        RecurrenceSpec spec,
        DateTimeOffset start,
        string? timeZoneId = null,
        IEnumerable<DateTimeOffset>? exceptions = null
    )
    {
        timeZoneId ??= DefaultTimeZoneId;

        if (!TimeZoneInfo.TryFindSystemTimeZoneById(timeZoneId, out _))
        {
            return new ApplicationError(
                ErrorTypes.InvalidTimeZone,
                $"'{timeZoneId}' is not a known IANA time zone."
            );
        }

        if (spec.Interval < 1)
            return new ApplicationError(ErrorTypes.InvalidInterval, "Interval must be at least 1.");

        if (spec.Count is not null && spec.Until is not null)
        {
            return new ApplicationError(
                ErrorTypes.InvalidRecurrenceRule,
                "Cannot specify both 'until' and 'count'. They are mutually exclusive."
            );
        }

        if (spec.Count is < 1)
            return new ApplicationError(ErrorTypes.InvalidRecurrenceRule, "Count must be at least 1.");

        if (spec.Until is not null && spec.Until < start)
        {
            return new ApplicationError(
                ErrorTypes.InvalidRecurrenceRule,
                "Start date must be before 'until' date."
            );
        }

        var rangeError =
            OutOfRange(spec.ByHour, 0, 23, ErrorTypes.InvalidHour, "ByHour", "0 to 23")
            ?? OutOfRange(spec.ByMinute, 0, 59, ErrorTypes.InvalidMinute, "ByMinute", "0 to 59")
            ?? OutOfRange(spec.ByMonth, 1, 12, ErrorTypes.InvalidMonth, "ByMonth", "1 to 12")
            ?? OutOfRange(spec.ByMonthDay, -31, 31, ErrorTypes.InvalidDayOfMonth, "ByMonthDay", "-31 to -1 and 1 to 31", allowZero: false)
            ?? OutOfRange(spec.BySetPos, -366, 366, ErrorTypes.PossibleInvalidSetPos, "BySetPos", "-366 to -1 and 1 to 366", allowZero: false);
        if (rangeError is not null)
            return rangeError;

        if (spec.BySetPos is { Count: > 0 } && spec.ByDay is not { Count: > 0 } && spec.ByMonthDay is not { Count: > 0 })
        {
            return new ApplicationError(
                ErrorTypes.PossibleInvalidSetPos,
                "BySetPos must be combined with ByDay or ByMonthDay."
            );
        }

        if (OutOfLimits(spec, start) is { } limitError)
            return limitError;

        var pattern = new RecurrencePattern(ToFrequencyType(spec.Frequency), spec.Interval)
        {
            Count = spec.Count,
            // Ical.Net requires UNTIL to be expressed in UTC.
            Until = spec.Until is null ? null : new CalDateTime(spec.Until.Value.UtcDateTime, "UTC"),
        };
        if (spec.ByHour is not null) pattern.ByHour.AddRange(spec.ByHour);
        if (spec.ByMinute is not null) pattern.ByMinute.AddRange(spec.ByMinute);
        if (spec.ByDay is not null) pattern.ByDay.AddRange(spec.ByDay.Select(d => new WeekDay(d)));
        if (spec.ByMonthDay is not null) pattern.ByMonthDay.AddRange(spec.ByMonthDay);
        if (spec.ByMonth is not null) pattern.ByMonth.AddRange(spec.ByMonth);
        if (spec.BySetPos is not null) pattern.BySetPosition.AddRange(spec.BySetPos);

        return new RecurrenceSchedule(pattern, timeZoneId, start, exceptions ?? []);
    }

    /// <summary>A rule that fires exactly once, at <paramref name="at"/>.</summary>
    public static OneOf<ApplicationError, RecurrenceSchedule> OneTime(
        DateTimeOffset at,
        string? timeZoneId = null
    ) => Create(new RecurrenceSpec(Frequency.Daily, Count: 1), at, timeZoneId);

    /// <summary>
    /// Rehydrates a schedule from persisted values. Stored data is trusted: an unparsable
    /// RRULE throws rather than returning an error.
    /// </summary>
    public static RecurrenceSchedule FromStored(
        string rrule,
        string timeZoneId,
        DateTimeOffset start,
        IEnumerable<DateTimeOffset>? exceptions = null
    ) => new(new RecurrencePattern(rrule), timeZoneId, start, exceptions ?? []);

    /// <summary>
    /// Occurrences with <c>from &lt;= occurrence &lt;= to</c>, ascending, at most <paramref name="limit"/> of them
    /// (never more than <see cref="MaxOccurrences"/>). Expansion stops at the limit, so a wide window over a dense
    /// rule costs what the caller keeps, not what the window holds.
    /// </summary>
    public IReadOnlyList<DateTimeOffset> GetOccurrences(DateTimeOffset from, DateTimeOffset to, int limit = MaxOccurrences)
    {
        if (to < from || limit < 1)
            return [];

        return [.. Expand(from).TakeWhile(d => d <= to).Take(Math.Min(limit, MaxOccurrences))];
    }

    /// <summary>The first occurrence strictly after <paramref name="after"/>, if any.</summary>
    public DateTimeOffset? GetNextOccurrence(DateTimeOffset after)
    {
        foreach (var occurrence in Expand(after))
        {
            if (occurrence > after)
                return occurrence;
        }

        return null;
    }

    /// <summary>
    /// Hourly and minute rules count elapsed time, so they are expanded from their start in UTC. Across a spring
    /// DST change that yields the instants Ical.Net computes in local time anyway; across an autumn one (the hour
    /// that happens twice) Ical.Net 5.2.3 never returns from local time. A one-time rule is exactly its start
    /// instant, which local time cannot always name (01:30 twice on a fall-back night).
    /// </summary>
    private bool ExpandsInUtc =>
        pattern.Frequency is FrequencyType.Minutely or FrequencyType.Hourly or FrequencyType.Secondly || pattern.Count == 1;

    private IEnumerable<DateTimeOffset> Expand(DateTimeOffset from)
    {
        var calendarEvent = new CalendarEvent
        {
            Start = ExpandsInUtc
                ? new CalDateTime(Start.UtcDateTime, "UTC")
                : new CalDateTime(TimeZoneInfo.ConvertTime(Start, TimeZoneInfo.FindSystemTimeZoneById(TimeZoneId)).DateTime, TimeZoneId),
            RecurrenceRule = pattern,
        };

        // Ask Ical.Net to start a little earlier than requested and filter ourselves, so the
        // result is independent of whether its lower bound is inclusive.
        var lowerBound = from < Start ? Start : from;
        var searchFrom = new CalDateTime(lowerBound.UtcDateTime.AddDays(-1), "UTC");

        // Exceptions are filtered after expansion so that COUNT keeps its RFC meaning
        // (it bounds the generated set; EXDATE removes from it).
        return calendarEvent
            .GetOccurrences(searchFrom)
            .Select(o => new DateTimeOffset(o.Period.StartTime.AsUtc, TimeSpan.Zero))
            .Where(d => d >= lowerBound && !exceptions.Contains(d));
    }

    private static ApplicationError? OutOfRange(
        IReadOnlyList<int>? values,
        int min,
        int max,
        ErrorTypes error,
        string name,
        string validRange,
        bool allowZero = true
    )
    {
        if (values is null)
            return null;

        foreach (var value in values)
        {
            if (value < min || value > max || (!allowZero && value == 0))
            {
                return new ApplicationError(
                    error,
                    $"{name} value '{value}' is out of valid range ({validRange})."
                );
            }
        }

        return null;
    }

    /// <summary>
    /// At most <see cref="MaxCount"/> repeats, an end within <see cref="MaxYears"/> years, and no more than
    /// <see cref="MaxPerDay"/> instants a day. Hourly and minute rules take no hour, day or month parts: they are
    /// expanded in UTC (<see cref="ExpandsInUtc"/>), where those parts would mean UTC hours and days, not the user's.
    /// </summary>
    private static ApplicationError? OutOfLimits(RecurrenceSpec spec, DateTimeOffset start)
    {
        if (spec.Count > MaxCount)
            return new ApplicationError(ErrorTypes.InvalidRecurrenceRule, "A rule can repeat at most 5,000 times.");

        if (spec.Until > start.AddYears(MaxYears))
            return new ApplicationError(ErrorTypes.InvalidRecurrenceRule, "A rule can run for at most 10 years.");

        if (spec.Frequency is Frequency.Minutely or Frequency.Hourly)
        {
            if (spec.Frequency == Frequency.Minutely && spec.Interval < MinMinuteInterval)
                return new ApplicationError(ErrorTypes.InvalidInterval, "A rule can repeat at most every 5 minutes.");

            int?[] parts = [spec.ByHour?.Count, spec.ByMinute?.Count, spec.ByDay?.Count, spec.ByMonthDay?.Count, spec.ByMonth?.Count, spec.BySetPos?.Count];
            return parts.Any(count => count > 0)
                ? new ApplicationError(ErrorTypes.InvalidRecurrenceRule, "An hourly or minute rule cannot be limited to certain hours, days or months.")
                : null;
        }

        var perDay = (spec.ByHour?.Distinct().Count() ?? 1) * (spec.ByMinute?.Distinct().Count() ?? 1);
        return perDay > MaxPerDay
            ? new ApplicationError(ErrorTypes.InvalidRecurrenceRule, "A rule can fire at most 288 times a day.")
            : null;
    }

    private static FrequencyType ToFrequencyType(Frequency frequency) =>
        frequency switch
        {
            Frequency.Minutely => FrequencyType.Minutely,
            Frequency.Hourly => FrequencyType.Hourly,
            Frequency.Daily => FrequencyType.Daily,
            Frequency.Weekly => FrequencyType.Weekly,
            Frequency.Monthly => FrequencyType.Monthly,
            Frequency.Yearly => FrequencyType.Yearly,
            _ => throw new ArgumentOutOfRangeException(nameof(frequency)),
        };

    private static Frequency FromFrequencyType(FrequencyType frequency) =>
        frequency switch
        {
            FrequencyType.Minutely => Frequency.Minutely,
            FrequencyType.Hourly => Frequency.Hourly,
            FrequencyType.Daily => Frequency.Daily,
            FrequencyType.Weekly => Frequency.Weekly,
            FrequencyType.Monthly => Frequency.Monthly,
            FrequencyType.Yearly => Frequency.Yearly,
            _ => throw new ArgumentOutOfRangeException(nameof(frequency)),
        };
}
