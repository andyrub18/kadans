using System.Globalization;
using System.Net;
using System.Net.Sockets;
using System.Threading.RateLimiting;
using Kadans.SharedKernel.Errors;
using Kadans.SharedKernel.Http;
using Microsoft.AspNetCore.RateLimiting;

namespace Kadans.Api;

/// <summary>Per-client limits. Production values live in appsettings.json; Development is generous for the smoke scripts.</summary>
internal sealed class RateLimitingOptions
{
    public const string SectionName = "RateLimiting";

    /// <summary><see cref="RateLimitPolicies.Email"/>: register, forgot password, resend confirmation, email change.</summary>
    public int EmailPer15Minutes { get; set; } = 5;

    /// <summary><see cref="RateLimitPolicies.Credentials"/>: sign-in, 2FA codes, password change.</summary>
    public int CredentialsPerMinute { get; set; } = 20;

    /// <summary>Every other request (health checks excepted).</summary>
    public int RequestsPerMinute { get; set; } = 300;
}

/// <summary>
/// ASP.NET Core's rate limiter, partitioned per client. Behind nginx the forwarded-headers middleware has already
/// replaced the connection's address with the real client's; nginx sets X-Forwarded-For itself, so a client cannot
/// pick its own partition. A rejection is a 429 ProblemDetails in the request's language, with Retry-After.
/// </summary>
internal static class RateLimiting
{
    public static IServiceCollection AddKadansRateLimiting(this IServiceCollection services, IConfiguration configuration)
    {
        var limits = configuration.GetSection(RateLimitingOptions.SectionName).Get<RateLimitingOptions>() ?? new RateLimitingOptions();

        return services.AddRateLimiter(options =>
        {
            options.RejectionStatusCode = StatusCodes.Status429TooManyRequests;
            options.OnRejected = OnRejectedAsync;

            options.GlobalLimiter = PartitionedRateLimiter.Create<HttpContext, string>(http =>
                // An uptime monitor polls these; they reveal nothing and cost one query.
                http.Request.Path.StartsWithSegments("/health")
                    ? RateLimitPartition.GetNoLimiter("health")
                    : RateLimitPartition.GetFixedWindowLimiter(
                        ClientOf(http),
                        _ => new FixedWindowRateLimiterOptions { PermitLimit = limits.RequestsPerMinute, Window = TimeSpan.FromMinutes(1) }
                    )
            );

            options.AddPolicy(RateLimitPolicies.Email, http => Bucket(ClientOf(http), limits.EmailPer15Minutes, TimeSpan.FromMinutes(15)));
            options.AddPolicy(RateLimitPolicies.Credentials, http => Bucket(ClientOf(http), limits.CredentialsPerMinute, TimeSpan.FromMinutes(1)));
        });
    }

    /// <summary>
    /// <paramref name="perWindow"/> requests at once at most, refilled one at a time over <paramref name="window"/>:
    /// no double burst at a window's edge, and the rejection can say exactly when to retry (Retry-After).
    /// </summary>
    private static RateLimitPartition<string> Bucket(string client, int perWindow, TimeSpan window)
    {
        var size = Math.Max(1, perWindow);
        return RateLimitPartition.GetTokenBucketLimiter(
            client,
            _ => new TokenBucketRateLimiterOptions
            {
                TokenLimit = size,
                TokensPerPeriod = 1,
                ReplenishmentPeriod = window / size,
                QueueLimit = 0,
            }
        );
    }

    /// <summary>
    /// The partition key: the client's IPv4 address, or its IPv6 /64. One IPv6 subscriber usually holds a whole
    /// /64, so limiting single addresses would hand them 2^64 fresh budgets.
    /// </summary>
    internal static string ClientOf(HttpContext http)
    {
        var address = http.Connection.RemoteIpAddress;
        if (address is null)
            return "unknown";
        if (address.IsIPv4MappedToIPv6)
            address = address.MapToIPv4();
        if (address.AddressFamily != AddressFamily.InterNetworkV6)
            return address.ToString();

        var bytes = address.GetAddressBytes();
        Array.Clear(bytes, 8, 8);
        return $"{new IPAddress(bytes)}/64";
    }

    private static async ValueTask OnRejectedAsync(OnRejectedContext context, CancellationToken cancellationToken)
    {
        var http = context.HttpContext;
        if (context.Lease.TryGetMetadata(MetadataName.RetryAfter, out var retryAfter))
            http.Response.Headers.RetryAfter = ((int)Math.Ceiling(retryAfter.TotalSeconds)).ToString(CultureInfo.InvariantCulture);

        http.RequestServices
            .GetRequiredService<ILoggerFactory>()
            .CreateLogger("Kadans.RateLimiting")
            .LogWarning("Rate limit reached by {Client} on {Method} {Path}", ClientOf(http), http.Request.Method, http.Request.Path);

        var problem = new ApplicationError(ErrorTypes.TooManyRequests, "Too many attempts. Try again in a moment.").ToProblemDetails(http);
        await TypedResults.Problem(problem).ExecuteAsync(http);
    }
}
