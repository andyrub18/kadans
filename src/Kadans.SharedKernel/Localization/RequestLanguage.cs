using Microsoft.AspNetCore.Http;

namespace Kadans.SharedKernel.Localization;

/// <summary>The language the current request wants its human-readable texts in: "en", "fr" or "ht".</summary>
public interface IRequestLanguage
{
    string Code { get; }
}

/// <summary>
/// Kadans speaks English, French and Haitian Creole. The client sends its in-app language as
/// <c>Accept-Language</c> on every call, which also covers anonymous requests (register, login)
/// where the server knows no user yet. Anything else — no header, another language, a background
/// job — is English.
/// </summary>
public static class RequestLanguage
{
    public const string Default = "en";
    public static readonly IReadOnlyList<string> Supported = ["en", "fr", "ht"];

    private const string ItemKey = "kadans.language";

    /// <summary>First supported language of an Accept-Language value, by q-value then order ("fr-HT, en;q=0.8" → "fr").</summary>
    public static string Resolve(string? acceptLanguage)
    {
        if (string.IsNullOrWhiteSpace(acceptLanguage))
            return Default;

        return acceptLanguage
                .Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
                .Select((entry, index) =>
                {
                    var parts = entry.Split(';', StringSplitOptions.TrimEntries);
                    var quality = 1.0;
                    foreach (var parameter in parts.Skip(1))
                    {
                        if (parameter.StartsWith("q=", StringComparison.OrdinalIgnoreCase)
                            && double.TryParse(parameter[2..], System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var q))
                            quality = q;
                    }
                    // "fr-HT" and "fr_CA" are French to us; region never changes the wording here.
                    var primary = parts[0].Split('-', '_')[0].ToLowerInvariant();
                    return (primary, quality, index);
                })
                .Where(candidate => candidate.quality > 0 && Supported.Contains(candidate.primary))
                .OrderByDescending(candidate => candidate.quality)
                .ThenBy(candidate => candidate.index)
                .Select(candidate => candidate.primary)
                .FirstOrDefault()
            ?? Default;
    }

    public static string Of(HttpContext? context)
    {
        if (context is null)
            return Default;
        if (context.Items.TryGetValue(ItemKey, out var cached) && cached is string known)
            return known;

        var resolved = Resolve(context.Request.Headers.AcceptLanguage.ToString());
        context.Items[ItemKey] = resolved;
        return resolved;
    }
}

public sealed class HttpRequestLanguage(IHttpContextAccessor accessor) : IRequestLanguage
{
    public string Code => RequestLanguage.Of(accessor.HttpContext);
}
