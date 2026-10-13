namespace Kadans.Modules.Tasks.Features.Pomodoro;

/// <summary>
/// Which occurrence a focus session is for, so its end can offer to mark it done (ARCHITECTURE → Pomodoro): the one a
/// reminder's "Start focus" named, else a one-time todo's, else the pending one nearest the start, at most
/// <see cref="Near"/> before or after it. None is fine: the session just offers nothing at its end.
/// </summary>
internal static class SessionOccurrence
{
    /// <summary>How far from the start an occurrence of a repeating todo can be and still be the one being worked on.</summary>
    public static readonly TimeSpan Near = TimeSpan.FromHours(12);

    /// <summary>
    /// The nearer of the last pending occurrence up to <paramref name="now"/> and the first after it; on a tie the
    /// one ahead (the person starts early rather than catches up).
    /// </summary>
    public static Guid? Nearest((Guid Id, DateTimeOffset At)? before, (Guid Id, DateTimeOffset At)? after, DateTimeOffset now) =>
        (before, after) switch
        {
            ({ } b, { } a) => now - b.At < a.At - now ? b.Id : a.Id,
            ({ } b, null) => b.Id,
            (null, { } a) => a.Id,
            _ => null,
        };
}
