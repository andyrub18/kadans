namespace Kadans.Modules.Tasks.Features.Reminders;

/// <summary>
/// When an occurrence's reminders ring (ARCHITECTURE → "Reminders ring on the phone"): its lead before the start, then
/// the start itself ("starts now"), so the moment to begin never passes in silence. A lead of zero is the start alone.
/// A todo whose focus session is running rings neither: the person has begun.
/// </summary>
internal static class ReminderMoments
{
    /// <summary>The moments, in order: none with notifications off; the start alone for a zero lead.</summary>
    public static IReadOnlyList<DateTimeOffset> Of(DateTimeOffset? notifyAt, DateTimeOffset startsAt) =>
        notifyAt switch
        {
            null => [],
            { } early when early < startsAt => [early, startsAt],
            _ => [startsAt],
        };

    /// <summary>
    /// Two instants are one moment when they agree to the millisecond: the apps hand back what they were given, and
    /// keep reminders by the millisecond.
    /// </summary>
    public static bool Same(DateTimeOffset a, DateTimeOffset b) => Math.Abs((a - b).Ticks) < TimeSpan.TicksPerMillisecond;

    /// <summary>Whether a reminder at <paramref name="at"/> is one of <paramref name="moments"/>.</summary>
    public static bool Includes(IReadOnlyList<DateTimeOffset> moments, DateTimeOffset at) => moments.Any(m => Same(m, at));

    /// <summary>
    /// A device's window: each occurrence's moments within (<paramref name="now"/>, <paramref name="through"/>], soonest
    /// first, at most <paramref name="cap"/>. <paramref name="occurrences"/> come by notify time; when the query was cut
    /// (<paramref name="cut"/>), only the moments up to the last one's notify time are known to be all there, so the
    /// window reaches that far. A window cut at the cap reaches its last reminder: the push covers what lies after it.
    /// </summary>
    public static (List<(T Occurrence, DateTimeOffset At)> Reminders, DateTimeOffset Through) Window<T>(
        IReadOnlyList<T> occurrences,
        Func<T, DateTimeOffset?> notifyAt,
        Func<T, DateTimeOffset> startsAt,
        DateTimeOffset now,
        DateTimeOffset through,
        bool cut,
        int cap
    )
    {
        if (cut && occurrences.Count > 0 && notifyAt(occurrences[^1]) is { } last && last < through)
            through = last;
        var reminders = occurrences
            .SelectMany(o => Of(notifyAt(o), startsAt(o)).Where(at => at > now && at <= through).Select(at => (o, at)))
            .OrderBy(r => r.at)
            .ToList();
        if (reminders.Count > cap)
        {
            reminders.RemoveRange(cap, reminders.Count - cap);
            through = reminders[^1].at;
        }
        return (reminders, through);
    }
}
