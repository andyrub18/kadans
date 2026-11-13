using System.Globalization;
using System.Security.Claims;
using System.Security.Cryptography;
using System.Text;
using System.Threading.RateLimiting;
using Kadans.SharedKernel.Users;
using Microsoft.Extensions.Options;
using Microsoft.Net.Http.Headers;

namespace Kadans.Modules.Tasks.Features.CalendarFeed;

/// <summary>
/// What one link may be read, wherever the reads come from: a calendar provider reads everyone's feed from the same
/// few addresses, so the host's per-address allowance cannot tell a link read too often from many links read once.
/// Asked only for a link that exists, so there are never more counters than links.
/// </summary>
internal sealed class CalendarFeedLimiter : IDisposable
{
    private readonly PartitionedRateLimiter<string> limiter;

    public CalendarFeedLimiter(IOptions<TasksOptions> options)
    {
        var perHour = Math.Max(1, options.Value.CalendarFeedFetchesPerHour);
        limiter = PartitionedRateLimiter.Create<string, string>(userId =>
            RateLimitPartition.GetTokenBucketLimiter(
                userId,
                _ => new TokenBucketRateLimiterOptions
                {
                    TokenLimit = perHour,
                    TokensPerPeriod = 1,
                    ReplenishmentPeriod = TimeSpan.FromHours(1) / perHour,
                    QueueLimit = 0,
                }
            )
        );
    }

    /// <summary>False once the link's hour is spent, with how long until the next read is due.</summary>
    public bool Allows(string userId, out TimeSpan retryAfter)
    {
        using var lease = limiter.AttemptAcquire(userId);
        retryAfter = lease.TryGetMetadata(MetadataName.RetryAfter, out var wait) ? wait : TimeSpan.FromMinutes(1);
        return lease.IsAcquired;
    }

    public void Dispose() => limiter.Dispose();
}

/// <summary>
/// The feed's own address, where a calendar app reads it with nothing but the link (ARCHITECTURE → "The calendar
/// feed"). A link that is not ours, or was reset, or whose account is closed, all get the same bare 404. A read that
/// would return the same file answers 304 (the file's hash is its ETag): calendar apps ask often, todos change rarely.
/// An account without the phones' subscription gets an empty calendar.
/// </summary>
internal sealed class CalendarFeedEndpoint(
    CalendarFeeds feeds,
    CalendarFeedReader reader,
    CalendarFeedLimiter limiter,
    IUserDirectory users,
    IMobileAccess mobileAccess,
    TasksMetrics metrics,
    TimeProvider time
)
{
    /// <summary>How the request's user was established: by a feed link, not by a sign-in.</summary>
    public const string AuthenticationType = "CalendarFeedLink";

    public async Task<IResult> Read(string secret, HttpContext context)
    {
        var cancellationToken = context.RequestAborted;
        var link = await feeds.FindBySecret(secret, cancellationToken);
        if (link is null)
            return Refused();

        if (!limiter.Allows(link.UserId, out var retryAfter))
        {
            metrics.CalendarFeedFetch("limited");
            context.Response.Headers.RetryAfter = ((int)Math.Ceiling(retryAfter.TotalSeconds)).ToString(CultureInfo.InvariantCulture);
            return TypedResults.StatusCode(StatusCodes.Status429TooManyRequests);
        }

        // Closed (the person asked to delete the account, or it was deactivated): nothing answers for it any more.
        if (!await users.IsOpenAsync(link.UserId, cancellationToken))
            return Refused();

        // The link stands for its owner: from here the request reads as them, through the same per-user filters as
        // their own app's requests. Nothing but this endpoint ever sees this identity.
        context.User = new ClaimsPrincipal(new ClaimsIdentity([new Claim(ClaimTypes.NameIdentifier, link.UserId)], AuthenticationType));

        // The feed is part of the phones' subscription. Without one the link is kept and its calendar is empty: a
        // refusal would leave the todos a calendar app already holds on show for good, and the link would have to be
        // given again once the subscription is back.
        var subscribed = await mobileAccess.AllowsPhonesAsync(link.UserId, cancellationToken);
        var now = time.GetUtcNow();
        var body = subscribed ? await reader.Read(now, cancellationToken) : reader.Empty();
        await feeds.Fetched(now, cancellationToken);

        var etag = new EntityTagHeaderValue($"\"{Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(body)).AsSpan(0, 16))}\"");
        var headers = context.Response.GetTypedHeaders();
        headers.ETag = etag;
        // Kept by the one who asked only, and checked with us before each use.
        headers.CacheControl = new CacheControlHeaderValue { Private = true, NoCache = true };

        var unchanged = context.Request.GetTypedHeaders().IfNoneMatch.Any(known => known.Compare(etag, useStrongComparison: false));
        metrics.CalendarFeedFetch(!subscribed ? "withheld" : unchanged ? "unchanged" : "served");
        return unchanged
            ? TypedResults.StatusCode(StatusCodes.Status304NotModified)
            : TypedResults.Text(body, CalendarFeedWriter.ContentType, Encoding.UTF8);
    }

    private IResult Refused()
    {
        metrics.CalendarFeedFetch("refused");
        return TypedResults.NotFound();
    }
}
