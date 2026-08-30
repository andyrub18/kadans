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
        this.exceptions = [.. exceptions];
        Rrule = new RecurrenceRuleSerializer().SerializeToString(pattern)
            ?? throw new InvalidOperationException("Could not serialize recurrence pattern.");
        TimeZoneId = timeZoneId;
        Start = start;
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

    /// <summary>Occurrences with <c>from &lt;= occurrence &lt;= to</c>, ascending.</summary>
    public IReadOnlyList<DateTimeOffset> GetOccurrences(DateTimeOffset from, DateTimeOffset to)
    {
        if (to < from)
            return [];

        return [.. Expand(from).TakeWhile(d => d <= to)];
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
    /// The last occurrence of a bounded rule (COUNT or UNTIL), or <c>null</c> for an
    /// indefinite one.
    /// </summary>
    public DateTimeOffset? GetLastOccurrence()
    {
        if (IsIndefinite)
            return null;

        DateTimeOffset? last = null;
        foreach (var occurrence in Expand(Start))
            last = occurrence;

        return last;
    }

    private IEnumerable<DateTimeOffset> Expand(DateTimeOffset from)
    {
        var timeZone = TimeZoneInfo.FindSystemTimeZoneById(TimeZoneId);
        var startWallClock = TimeZoneInfo.ConvertTime(Start, timeZone).DateTime;

        var calendarEvent = new CalendarEvent
        {
            Start = new CalDateTime(startWallClock, TimeZoneId),
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
