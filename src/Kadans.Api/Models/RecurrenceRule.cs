using Kadans.SharedKernel.Errors;
using OneOf;

namespace Kadans.Api.Models;

public enum Frequency
{
    Minutely,
    Hourly,
    Daily,
    Weekly,
    Monthly,
    Yearly,
}

public sealed class RecurrenceRule
{
    public Guid Id { get; set; } = Guid.CreateVersion7();
    public Frequency Frequency { get; private set; } = Frequency.Daily;
    public int Interval { get; private set; } = 1;
    public DateTimeOffset StartDate { get; private set; }

    // Time specifications
    public List<int>? ByHour { get; private set; }
    public List<int>? ByMinute { get; private set; }

    // Day/Month specifications
    public List<DayOfWeek>? ByDay { get; private set; }
    public List<int>? ByMonthDay { get; private set; }
    public List<int>? ByMonth { get; private set; }

    // Position specification (for things like "first Monday", "last Friday")
    // Positive values (1-5) = 1st, 2nd, 3rd, 4th, 5th occurrence
    // Negative values (-1 to -5) = last, second-to-last, etc.
    // Used in combination with ByDay
    public List<int>? BySetPos { get; private set; }

    // TERMINATION CONDITIONS (mutually exclusive)
    // Option 1: End on specific date
    public DateTimeOffset? Until { get; private set; }

    // Option 2: End after N occurrences
    public int? Count { get; private set; }

    // Option 3: No end date (indefinite)
    // If Until and Count are both null, it's indefinite

    // EXCEPTIONS
    public List<DateTimeOffset>? Exceptions { get; set; }

    // Helper properties (computed)
    public bool IsIndefinite => !Until.HasValue && !Count.HasValue;
    public bool HasEndDate => Until.HasValue;
    public bool HasMaxOccurrences => Count.HasValue;
    public bool IsOneTime => Count == 1;

    private RecurrenceRule() { }

    private RecurrenceRule(
        Frequency frequency,
        DateTimeOffset startDate,
        int interval = 1,
        List<int>? byHour = null,
        List<int>? byMinute = null,
        List<DayOfWeek>? byDay = null,
        List<int>? byMonthDay = null,
        List<int>? byMonth = null,
        List<int>? bySetPos = null,
        DateTimeOffset? until = null,
        int? count = null,
        List<DateTimeOffset>? exceptions = null
    )
    {
        Id = Guid.CreateVersion7();
        Frequency = frequency;
        Interval = interval;
        StartDate = startDate;
        ByHour = byHour;
        ByMinute = byMinute;
        ByDay = byDay;
        ByMonthDay = byMonthDay;
        ByMonth = byMonth;
        BySetPos = bySetPos;
        Until = until;
        Count = count;
        Exceptions = exceptions;
    }

    public static OneOf<ApplicationError, RecurrenceRule> CreateOneTimeRule(DateTimeOffset dueDate)
    {
        if (dueDate < DateTime.UtcNow)
        {
            return new ApplicationError(
                ErrorTypes.InvalidStartDate,
                "Due date cannot be in the past."
            );
        }

        // Mirror the defaults Create() applies for Daily rules; without ByHour/ByMinute
        // the candidate generator dereferences null.
        return new RecurrenceRule(
            Frequency.Daily,
            startDate: dueDate,
            byHour: [dueDate.Hour],
            byMinute: [dueDate.Minute],
            count: 1
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
        List<DateTimeOffset>? exceptions = null
    )
    {
        if (interval < 1)
        {
            return new ApplicationError(ErrorTypes.InvalidInterval, "Interval must be at least 1.");
        }

        if (until is not null && count is not null)
        {
            return new ApplicationError(
                ErrorTypes.InvalidRecurrenceRule,
                "Cannot specify both 'until' and 'count'. They are mutually exclusive."
            );
        }

        if (startDate < DateTime.UtcNow)
        {
            return new ApplicationError(
                ErrorTypes.InvalidStartDate,
                "Start date cannot be in the past."
            );
        }

        if (until is not null)
        {
            if (startDate > until)
            {
                return new ApplicationError(
                    ErrorTypes.InvalidRecurrenceRule,
                    "Start date must be before 'until' date."
                );
            }
        }

        switch (frequency)
        {
            case Frequency.Minutely:
                // No additional validation needed for minutely frequency
                // it will occur every 'interval' minutes from startDate
                break;
            case Frequency.Hourly:
            {
                // The task will occur every 'interval' hours at the specified minutes
                // if the minute is not specified, it defaults to the time of startDate
                if (byMinute is null || byMinute.Count == 0)
                {
                    byMinute = [startDate.Minute];
                }

                foreach (var minute in byMinute)
                {
                    if (minute is < 0 or > 59)
                    {
                        return new ApplicationError(
                            ErrorTypes.InvalidMinute,
                            $"ByMinute value '{minute}' is out of valid range (0 to 59)."
                        );
                    }
                }
                break;
            }
            case Frequency.Daily:
            {
                // The task will occur every 'interval' days at the specified hours and minutes
                // if hour and minute are not specified, it defaults to the time of startDate
                if (byHour is null || byHour.Count == 0)
                {
                    byHour = [startDate.Hour];
                }
                if (byMinute is null || byMinute.Count == 0)
                {
                    byMinute = [startDate.Minute];
                }
                foreach (var hour in byHour)
                {
                    if (hour is < 0 or > 23)
                    {
                        return new ApplicationError(
                            ErrorTypes.InvalidHour,
                            $"ByHour value '{hour}' is out of valid range (0 to 23)."
                        );
                    }
                }
                foreach (var minute in byMinute)
                {
                    if (minute is < 0 or > 59)
                    {
                        return new ApplicationError(
                            ErrorTypes.InvalidMinute,
                            $"ByMinute value '{minute}' is out of valid range (0 to 59)."
                        );
                    }
                }
                break;
            }
            case Frequency.Weekly:
            {
                // The task will occur every 'interval' weeks on the specified days of the week
                // at the specified hours and minutes
                // if days, hour, and minute are not specified, it defaults to the day and time of startDate
                if (byDayOfWeek is null || byDayOfWeek.Count == 0)
                {
                    byDayOfWeek = [startDate.DayOfWeek];
                }
                if (byHour is null || byHour.Count == 0)
                {
                    byHour = [startDate.Hour];
                }
                if (byMinute is null || byMinute.Count == 0)
                {
                    byMinute = [startDate.Minute];
                }

                foreach (var hour in byHour)
                {
                    if (hour is < 0 or > 23)
                    {
                        return new ApplicationError(
                            ErrorTypes.InvalidHour,
                            $"ByHour value '{hour}' is out of valid range (0 to 23)."
                        );
                    }
                }
                foreach (var minute in byMinute)
                {
                    if (minute is < 0 or > 59)
                    {
                        return new ApplicationError(
                            ErrorTypes.InvalidMinute,
                            $"ByMinute value '{minute}' is out of valid range (0 to 59)."
                        );
                    }
                }
                break;
            }
            case Frequency.Monthly:
            {
                // The valid range for month days is 1 to 31 and -1 to -31
                // But if the month day exceeds the number of days in a month, it will be ignored for that month
                // The task will occur every 'interval' months on the specified month days or by set positions
                // at the specified hours and minutes
                // if month day/set position, hour, and minute are not specified, it defaults to the day and time of startDate
                if (byMonthDay is null && byDayOfWeek is null)
                {
                    byMonthDay = [startDate.Day];
                }

                // validate ByMonthDay values
                if (byDayOfWeek is not null && bySetPos is not null)
                {
                    // validate BySetPos values
                    foreach (var pos in bySetPos)
                    {
                        if (pos is < -5 or 0 or > 5)
                        {
                            return new ApplicationError(
                                ErrorTypes.PossibleInvalidSetPos,
                                $"BySetPos value '{pos}' is out of valid range (-5 to -1 and 1 to 5)."
                            );
                        }
                    }
                }

                if (byHour is null || byHour.Count == 0)
                {
                    byHour = [startDate.Hour];
                }
                if (byMinute is null || byMinute.Count == 0)
                {
                    byMinute = [startDate.Minute];
                }
                if (byMonthDay is not null)
                {
                    foreach (var monthDay in byMonthDay)
                    {
                        if (monthDay is < -31 or 0 or > 31)
                        {
                            return new ApplicationError(
                                ErrorTypes.InvalidDayOfMonth,
                                $"ByMonthDay value '{monthDay}' is out of valid range (-31 to -1 and 1 to 31)."
                            );
                        }
                    }
                }
                foreach (var hour in byHour)
                {
                    if (hour is < 0 or > 23)
                    {
                        return new ApplicationError(
                            ErrorTypes.InvalidHour,
                            $"ByHour value '{hour}' is out of valid range (0 to 23)."
                        );
                    }
                }
                foreach (var minute in byMinute)
                {
                    if (minute is < 0 or > 59)
                    {
                        return new ApplicationError(
                            ErrorTypes.InvalidMinute,
                            $"ByMinute value '{minute}' is out of valid range (0 to 59)."
                        );
                    }
                }
                break;
            }
            case Frequency.Yearly:
            {
                // Validate ByMonth values
                byMonth ??= [startDate.Month];

                // The valid range for month days is 1 to 31 and -1 to -31
                // But if the month day exceeds the number of days in a month, it will be ignored for that month
                // The task will occur every 'interval' months on the specified month days or by set positions
                // at the specified hours and minutes
                // if month day/set position, hour, and minute are not specified, it defaults to the day and time of startDate
                foreach (var month in byMonth)
                {
                    if (month is < 1 or > 12)
                    {
                        return new ApplicationError(
                            ErrorTypes.InvalidMonth,
                            $"ByMonth value '{month}' is out of valid range (1 to 12)."
                        );
                    }
                }
                if (byMonthDay is null && byDayOfWeek is null)
                {
                    byMonthDay = [startDate.Day];
                }

                // validate ByMonthDay values
                if (byDayOfWeek is not null && bySetPos is not null)
                {
                    // validate BySetPos values
                    foreach (var pos in bySetPos)
                    {
                        if (pos is < -5 or 0 or > 5)
                        {
                            return new ApplicationError(
                                ErrorTypes.PossibleInvalidSetPos,
                                $"BySetPos value '{pos}' is out of valid range (-5 to -1 and 1 to 5)."
                            );
                        }
                    }
                }

                if (byHour is null || byHour.Count == 0)
                {
                    byHour = [startDate.Hour];
                }
                if (byMinute is null || byMinute.Count == 0)
                {
                    byMinute = [startDate.Minute];
                }

                if (byMonthDay is not null)
                {
                    foreach (var monthDay in byMonthDay)
                    {
                        if (monthDay is < -31 or 0 or > 31)
                        {
                            return new ApplicationError(
                                ErrorTypes.InvalidDayOfMonth,
                                $"ByMonthDay value '{monthDay}' is out of valid range (-31 to -1 and 1 to 31)."
                            );
                        }
                    }
                }

                foreach (var hour in byHour)
                {
                    if (hour is < 0 or > 23)
                    {
                        return new ApplicationError(
                            ErrorTypes.InvalidHour,
                            $"ByHour value '{hour}' is out of valid range (0 to 23)."
                        );
                    }
                }
                foreach (var minute in byMinute)
                {
                    if (minute is < 0 or > 59)
                    {
                        return new ApplicationError(
                            ErrorTypes.InvalidMinute,
                            $"ByMinute value '{minute}' is out of valid range (0 to 59)."
                        );
                    }
                }
                break;
            }
            default: // it will never reach here
                return new ApplicationError(
                    ErrorTypes.InvalidFrequency,
                    "Invalid frequency specified."
                );
        }

        return new RecurrenceRule(
            frequency: frequency,
            startDate: startDate,
            interval: interval,
            byHour: byHour,
            byMinute: byMinute,
            byDay: byDayOfWeek,
            byMonthDay: byMonthDay,
            bySetPos: bySetPos,
            byMonth: byMonth,
            count: count,
            until: until,
            exceptions: exceptions
        );
    }

    public List<DateTimeOffset> GetOccurrences(DateTimeOffset startDate, DateTimeOffset endDate)
    {
        List<DateTimeOffset> occurrences = [];

        // Determine the effective start date (can't be before the rule's start date)
        var currentDate = StartDate > startDate ? StartDate : startDate;

        // Determine the effective end date (consider Until and Count)
        var effectiveEndDate = endDate;
        if (Until.HasValue && Until.Value < effectiveEndDate)
        {
            effectiveEndDate = Until.Value;
        }

        var occurrenceCount = 0;
        var iterationDate = StartDate;

        while (iterationDate <= effectiveEndDate)
        {
            // Check if we've reached the count limit
            if (Count.HasValue && occurrenceCount >= Count.Value)
            {
                break;
            }

            // Generate candidates for the current iteration period
            var candidates = GenerateCandidates(iterationDate);

            foreach (var candidate in candidates)
            {
                // Skip if before the requested start date
                if (candidate < currentDate)
                {
                    continue;
                }

                // Stop if beyond the effective end date
                if (candidate > effectiveEndDate)
                {
                    break;
                }

                // Check if we've reached the count limit
                if (occurrenceCount >= Count)
                {
                    break;
                }

                // Skip exceptions
                if (Exceptions?.Contains(candidate) == true)
                {
                    continue;
                }

                occurrences.Add(candidate);
                occurrenceCount++;
            }

            // Move to the next iteration period
            iterationDate = AdvanceByInterval(iterationDate);

            // Safety check to prevent infinite loops
            if (iterationDate > DateTime.MaxValue.AddYears(-1))
            {
                break;
            }
        }

        return occurrences.OrderBy(d => d).ToList();
    }

    public DateTimeOffset? GetNextOccurrence()
    {
        var currentDate = DateTimeOffset.UtcNow.AddSeconds(1);

        // If we have Until and it's in the past, no next occurrence
        if (Until.HasValue && Until.Value < currentDate)
        {
            return null;
        }

        var occurrenceCount = 0;
        var iterationDate = StartDate;

        // Skip to a reasonable starting point if startDate is far in the past
        if (iterationDate < currentDate)
        {
            iterationDate = currentDate;
        }

        // Limit iterations to prevent infinite loops
        var maxIterations = 10000;
        var iterations = 0;

        while (iterations < maxIterations)
        {
            iterations++;

            // Check termination conditions
            if (Count.HasValue && occurrenceCount >= Count.Value)
            {
                return null;
            }

            if (Until.HasValue && iterationDate > Until.Value)
            {
                return null;
            }

            // Generate candidates for the current iteration period
            var candidates = GenerateCandidates(iterationDate);

            foreach (var candidate in candidates.OrderBy(c => c))
            {
                // Skip if before current time
                if (candidate < currentDate)
                {
                    continue;
                }

                // Check if beyond Until
                if (Until.HasValue && candidate > Until.Value)
                {
                    return null;
                }

                // Check if we've reached the count limit
                if (Count.HasValue && occurrenceCount >= Count.Value)
                {
                    return null;
                }

                // Skip exceptions
                if (Exceptions?.Contains(candidate) == true)
                {
                    occurrenceCount++;
                    continue;
                }

                // Found the next occurrence!
                return candidate;
            }

            // Move to the next iteration period
            iterationDate = AdvanceByInterval(iterationDate);
        }

        return null;
    }

    public DateTimeOffset? GetEffectiveEndDate()
    {
        // If Until is specified, return it
        if (Until.HasValue)
        {
            return Until.Value;
        }

        // If Count is not specified, it's indefinite
        if (!Count.HasValue)
        {
            return null;
        }

        // Calculate the date of the last occurrence based on Count
        var occurrenceCount = 0;
        var iterationDate = StartDate;
        DateTimeOffset? lastOccurrence = null;

        // Limit iterations to prevent infinite loops
        const int maxIterations = 100000;
        var iterations = 0;

        while (iterations < maxIterations)
        {
            iterations++;

            // Generate candidates for the current iteration period
            var candidates = GenerateCandidates(iterationDate);

            foreach (var candidate in candidates.OrderBy(c => c))
            {
                // Skip exceptions
                if (Exceptions?.Contains(candidate) == true)
                {
                    continue;
                }

                occurrenceCount++;
                lastOccurrence = candidate;

                // If we've reached the count, return this date
                if (occurrenceCount >= Count.Value)
                {
                    return lastOccurrence;
                }
            }

            // Move to the next iteration period
            iterationDate = AdvanceByInterval(iterationDate);

            // Safety check
            if (iterationDate > DateTime.MaxValue.AddYears(-1))
            {
                break;
            }
        }

        return lastOccurrence;
    }

    private DateTimeOffset AdvanceByInterval(DateTimeOffset date)
    {
        return Frequency switch
        {
            Frequency.Minutely => date.AddMinutes(Interval),
            Frequency.Hourly => date.AddHours(Interval),
            Frequency.Daily => date.AddDays(Interval),
            Frequency.Weekly => date.AddDays(7 * Interval),
            Frequency.Monthly => date.AddMonths(Interval),
            Frequency.Yearly => date.AddYears(Interval),
            _ => throw new InvalidOperationException("Invalid frequency"),
        };
    }

    private List<DateTimeOffset> GenerateCandidates(DateTimeOffset baseDate)
    {
        List<DateTimeOffset> candidates = [];

        switch (Frequency)
        {
            case Frequency.Minutely:
                candidates.Add(baseDate);
                break;

            case Frequency.Hourly:
                foreach (var minute in ByMinute!)
                {
                    candidates.Add(
                        new DateTime(
                            baseDate.Year,
                            baseDate.Month,
                            baseDate.Day,
                            baseDate.Hour,
                            minute,
                            0,
                            DateTimeKind.Utc
                        )
                    );
                }
                break;

            case Frequency.Daily:
                foreach (var hour in ByHour!)
                {
                    foreach (var minute in ByMinute!)
                    {
                        candidates.Add(
                            new DateTime(
                                baseDate.Year,
                                baseDate.Month,
                                baseDate.Day,
                                hour,
                                minute,
                                0,
                                DateTimeKind.Utc
                            )
                        );
                    }
                }
                break;

            case Frequency.Weekly:
                foreach (var dayOfWeek in ByDay!)
                {
                    var daysUntilTarget = ((int)dayOfWeek - (int)baseDate.DayOfWeek + 7) % 7;
                    var targetDate = baseDate.AddDays(daysUntilTarget);

                    foreach (var hour in ByHour!)
                    {
                        foreach (var minute in ByMinute!)
                        {
                            candidates.Add(
                                new DateTime(
                                    targetDate.Year,
                                    targetDate.Month,
                                    targetDate.Day,
                                    hour,
                                    minute,
                                    0,
                                    DateTimeKind.Utc
                                )
                            );
                        }
                    }
                }
                break;

            case Frequency.Monthly:
                candidates.AddRange(GenerateMonthlyCandidates(baseDate));
                break;

            case Frequency.Yearly:
                foreach (var month in ByMonth!)
                {
                    var yearBaseDate = new DateTime(
                        baseDate.Year,
                        month,
                        1,
                        0,
                        0,
                        0,
                        DateTimeKind.Utc
                    );
                    candidates.AddRange(GenerateMonthlyCandidates(yearBaseDate));
                }
                break;
        }

        return candidates;
    }

    private List<DateTimeOffset> GenerateMonthlyCandidates(DateTimeOffset baseDate)
    {
        List<DateTimeOffset> candidates = [];

        if (ByMonthDay != null)
        {
            var daysInMonth = DateTime.DaysInMonth(baseDate.Year, baseDate.Month);

            foreach (var monthDay in ByMonthDay)
            {
                int actualDay;
                if (monthDay > 0)
                {
                    actualDay = Math.Min(monthDay, daysInMonth);
                }
                else
                {
                    actualDay = daysInMonth + monthDay + 1;
                    if (actualDay < 1)
                        continue;
                }

                foreach (var hour in ByHour!)
                {
                    foreach (var minute in ByMinute!)
                    {
                        candidates.Add(
                            new DateTime(
                                baseDate.Year,
                                baseDate.Month,
                                actualDay,
                                hour,
                                minute,
                                0,
                                DateTimeKind.Utc
                            )
                        );
                    }
                }
            }
        }
        else if (ByDay != null)
        {
            var allDaysOfWeek = GetAllDaysOfWeekInMonth(baseDate, ByDay);

            if (BySetPos != null)
            {
                List<DateTimeOffset> filtered = [];
                foreach (var pos in BySetPos)
                {
                    if (pos > 0 && pos <= allDaysOfWeek.Count)
                    {
                        filtered.Add(allDaysOfWeek[pos - 1]);
                    }
                    else if (pos < 0 && Math.Abs(pos) <= allDaysOfWeek.Count)
                    {
                        filtered.Add(allDaysOfWeek[allDaysOfWeek.Count + pos]);
                    }
                }
                allDaysOfWeek = filtered;
            }

            foreach (var day in allDaysOfWeek)
            {
                foreach (var hour in ByHour!)
                {
                    foreach (var minute in ByMinute!)
                    {
                        candidates.Add(
                            new DateTime(
                                day.Year,
                                day.Month,
                                day.Day,
                                hour,
                                minute,
                                0,
                                DateTimeKind.Utc
                            )
                        );
                    }
                }
            }
        }

        return candidates;
    }

    private static List<DateTimeOffset> GetAllDaysOfWeekInMonth(
        DateTimeOffset baseDate,
        List<DayOfWeek> daysOfWeek
    )
    {
        List<DateTimeOffset> result = [];
        var firstDayOfMonth = new DateTimeOffset(
            new(baseDate.Year, baseDate.Month, 1, 0, 0, 0, DateTimeKind.Utc)
        );
        var daysInMonth = DateTime.DaysInMonth(baseDate.Year, baseDate.Month);

        foreach (var targetDay in daysOfWeek)
        {
            for (var day = 1; day <= daysInMonth; day++)
            {
                var date = new DateTimeOffset(
                    new(baseDate.Year, baseDate.Month, day, 0, 0, 0, DateTimeKind.Utc)
                );
                if (date.DayOfWeek == targetDay)
                {
                    result.Add(date);
                }
            }
        }

        return result.OrderBy(d => d).ToList();
    }
}
