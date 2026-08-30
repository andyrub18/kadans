using System.ComponentModel.DataAnnotations.Schema;
using Kadans.SharedKernel.Errors;
using Kadans.SharedKernel.Recurrence;
using OneOf;

namespace Kadans.Modules.Tasks.Domain;

/// <summary>
/// Persistence wrapper around <see cref="RecurrenceSchedule"/>: stores the RRULE, time zone,
/// start and exceptions, and delegates every computation to the schedule.
/// </summary>
internal sealed class RecurrenceRule
{
    private RecurrenceSchedule? schedule;

    public Guid Id { get; init; } = Guid.CreateVersion7();
    public string Rrule { get; private set; } = string.Empty;
    public string TimeZoneId { get; private set; } = RecurrenceSchedule.DefaultTimeZoneId;
    public DateTimeOffset StartDate { get; private set; }
    public List<DateTimeOffset> Exceptions { get; private set; } = [];

    [NotMapped]
    public RecurrenceSchedule Schedule =>
        schedule ??= RecurrenceSchedule.FromStored(Rrule, TimeZoneId, StartDate, Exceptions);

    public Frequency Frequency => Schedule.Frequency;
    public int Interval => Schedule.Interval;
    public int? Count => Schedule.Count;
    public DateTimeOffset? Until => Schedule.Until;
    public bool IsIndefinite => Schedule.IsIndefinite;
    public bool HasEndDate => Until.HasValue;
    public bool HasMaxOccurrences => Count.HasValue;
    public bool IsOneTime => Schedule.IsOneTime;

    private RecurrenceRule() { }

    private RecurrenceRule(RecurrenceSchedule schedule)
    {
        this.schedule = schedule;
        Rrule = schedule.Rrule;
        TimeZoneId = schedule.TimeZoneId;
        StartDate = schedule.Start;
        Exceptions = [.. schedule.Exceptions];
    }

    public static OneOf<ApplicationError, RecurrenceRule> CreateOneTimeRule(
        DateTimeOffset dueDate,
        string? timeZoneId = null
    )
    {
        if (dueDate < DateTimeOffset.UtcNow)
        {
            return new ApplicationError(
                ErrorTypes.InvalidStartDate,
                "Due date cannot be in the past."
            );
        }

        return RecurrenceSchedule
            .OneTime(dueDate, timeZoneId)
            .Match<OneOf<ApplicationError, RecurrenceRule>>(
                error => error,
                created => new RecurrenceRule(created)
            );
    }

    public static OneOf<ApplicationError, RecurrenceRule> Create(
        Frequency frequency,
        DateTimeOffset startDate,
        int interval = 1,
        List<int>? byHour = null,
        List<int>? byMinute = null,
        List<DayOfWeek>? byDayOfWeek = null,
        List<int>? byMonthDay = null,
        List<int>? bySetPos = null,
        List<int>? byMonth = null,
        int? count = null,
        DateTimeOffset? until = null,
        List<DateTimeOffset>? exceptions = null,
        string? timeZoneId = null
    )
    {
        if (startDate < DateTimeOffset.UtcNow)
        {
            return new ApplicationError(
                ErrorTypes.InvalidStartDate,
                "Start date cannot be in the past."
            );
        }

        var spec = new RecurrenceSpec(
            frequency,
            interval,
            byHour,
            byMinute,
            byDayOfWeek,
            byMonthDay,
            byMonth,
            bySetPos,
            count,
            until
        );

        return RecurrenceSchedule
            .Create(spec, startDate, timeZoneId, exceptions)
            .Match<OneOf<ApplicationError, RecurrenceRule>>(
                error => error,
                created => new RecurrenceRule(created)
            );
    }

    /// <summary>Excludes one occurrence instant from the rule.</summary>
    public void AddException(DateTimeOffset occurrence)
    {
        Exceptions.Add(occurrence.ToUniversalTime());
        schedule = null;
    }

    public IReadOnlyList<DateTimeOffset> GetOccurrences(DateTimeOffset from, DateTimeOffset to) =>
        Schedule.GetOccurrences(from, to);

    public DateTimeOffset? GetNextOccurrence() => GetNextOccurrence(DateTimeOffset.UtcNow);

    public DateTimeOffset? GetNextOccurrence(DateTimeOffset after) =>
        Schedule.GetNextOccurrence(after);

    /// <summary>UNTIL when set, otherwise the last occurrence of a COUNT-bounded rule.</summary>
    public DateTimeOffset? GetEffectiveEndDate() => Until ?? Schedule.GetLastOccurrence();
}
