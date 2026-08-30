namespace Kadans.SharedKernel.Recurrence;

/// <summary>
/// Structured description of a recurrence, as accepted from clients. Parts that are omitted
/// take their value from the start date, following RFC 5545 (e.g. a daily rule without
/// <see cref="ByHour"/> fires at the start date's wall-clock time).
/// </summary>
public sealed record RecurrenceSpec(
    Frequency Frequency,
    int Interval = 1,
    IReadOnlyList<int>? ByHour = null,
    IReadOnlyList<int>? ByMinute = null,
    IReadOnlyList<DayOfWeek>? ByDay = null,
    IReadOnlyList<int>? ByMonthDay = null,
    IReadOnlyList<int>? ByMonth = null,
    IReadOnlyList<int>? BySetPos = null,
    int? Count = null,
    DateTimeOffset? Until = null
);
