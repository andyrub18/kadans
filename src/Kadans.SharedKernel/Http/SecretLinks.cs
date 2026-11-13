using Microsoft.AspNetCore.Http;

namespace Kadans.SharedKernel.Http;

/// <summary>
/// Addresses that are their own credential: whoever holds one is let in, without signing in, because what reads it
/// (a calendar app, a calendar provider's servers) can send nothing else. The secret is in the path, so the path
/// must never be written anywhere: the host cuts it out of everything it logs (<see cref="Hide"/>, on the whole log
/// pipeline: ASP.NET attaches the request's path to every line written while a request runs), and gives these
/// addresses their own rate allowance. A module maps its route under one of the prefixes below.
/// </summary>
public static class SecretLinks
{
    /// <summary>The calendar feed (Tasks): <c>/calendar/feed/{secret}/kadans.ics</c>.</summary>
    public const string CalendarFeed = "/calendar/feed";

    /// <summary>What a log shows in place of everything after a secret link's prefix.</summary>
    public const string Hidden = "/…";

    /// <summary>True for any path under a secret link's prefix, matched by a route or not (a mistyped one too).</summary>
    public static bool Holds(PathString path) => path.StartsWithSegments(CalendarFeed, StringComparison.OrdinalIgnoreCase);

    /// <summary>The path as a log may write it: a secret link's stops at its prefix.</summary>
    public static string Loggable(PathString path) => Holds(path) ? CalendarFeed + Hidden : path.ToString();

    /// <summary>
    /// Any text that holds a secret link (a path, a whole URL, a sentence quoting one), cut after the link's prefix;
    /// null when it holds none. For what the host cannot ask <see cref="Loggable"/> about: values other code logged.
    /// </summary>
    public static string? Hide(string text)
    {
        var at = text.IndexOf(CalendarFeed + "/", StringComparison.OrdinalIgnoreCase);
        return at < 0 ? null : string.Concat(text.AsSpan(0, at), CalendarFeed, Hidden);
    }
}
