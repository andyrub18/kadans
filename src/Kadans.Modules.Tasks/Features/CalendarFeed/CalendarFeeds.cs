using Kadans.Modules.Tasks.Contracts;
using Kadans.Modules.Tasks.Domain;
using Kadans.Modules.Tasks.Persistence;
using Kadans.SharedKernel.Email;
using Kadans.SharedKernel.Errors;
using Kadans.SharedKernel.Security;
using Kadans.SharedKernel.Users;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using OneOf;
using OneOf.Types;

namespace Kadans.Modules.Tasks.Features.CalendarFeed;

/// <summary>
/// The account's calendar feed, as Settings turns it on and off (ARCHITECTURE → "The calendar feed"): one link per
/// account, shown again whenever asked, replaced by "reset", gone with "off". The logs say that it changed, never
/// the link.
/// </summary>
internal sealed class CalendarFeeds(
    TasksDbContext dbContext,
    ICurrentUserService currentUser,
    CalendarFeedLinks links,
    IOptions<EmailOptions> addresses,
    IMobileAccess mobileAccess,
    TimeProvider time,
    ILogger<CalendarFeeds> logger
)
{
    public async Task<CalendarFeedResponse> State(CancellationToken cancellationToken) =>
        Response(await dbContext.CalendarFeedLinks.AsNoTracking().FirstOrDefaultAsync(cancellationToken)) with
        {
            Available = currentUser.UserId is { } userId && await mobileAccess.AllowsPhonesAsync(userId, cancellationToken),
        };

    /// <summary>
    /// The feed puts the todos on a phone, in its calendar app: it is part of what the phones' subscription pays for
    /// (IMobileAccess), or the free desktop app would be a way around it. Always allowed while subscriptions are not
    /// required. Turning it off needs none.
    /// </summary>
    private async Task<ApplicationError?> WithoutSubscription(string userId, CancellationToken cancellationToken) =>
        await mobileAccess.AllowsPhonesAsync(userId, cancellationToken)
            ? null
            : new ApplicationError(ErrorTypes.SubscriptionRequired, "The calendar link is part of the phone subscription.");

    /// <summary>On, with the link it already has when it was on: turning it on twice changes nothing.</summary>
    public async Task<OneOf<ApplicationError, CalendarFeedResponse>> TurnOn(CancellationToken cancellationToken)
    {
        if (currentUser.UserId is not { } userId)
            return new ApplicationError(ErrorTypes.Unauthorized, "User must be authenticated.");
        if (await WithoutSubscription(userId, cancellationToken) is { } refused)
            return refused;

        var link = await dbContext.CalendarFeedLinks.FirstOrDefaultAsync(cancellationToken);
        if (link is not null)
            return Response(link);

        link = new CalendarFeedLink { UserId = userId };
        dbContext.CalendarFeedLinks.Add(link);
        try
        {
            await dbContext.SaveChangesAsync(cancellationToken);
            logger.LogInformation("User {UserId} turned the calendar feed on", userId);
        }
        catch (DbUpdateException)
        {
            // Turned on from another device at the same moment: that one's link is the account's.
            dbContext.Entry(link).State = EntityState.Detached;
            link = await dbContext.CalendarFeedLinks.AsNoTracking().FirstAsync(cancellationToken);
        }

        return Response(link);
    }

    /// <summary>A new link: whoever holds the old one reads nothing more. On an account whose feed was off, it turns it on.</summary>
    public async Task<OneOf<ApplicationError, CalendarFeedResponse>> Reset(CancellationToken cancellationToken)
    {
        if (currentUser.UserId is not { } userId)
            return new ApplicationError(ErrorTypes.Unauthorized, "User must be authenticated.");
        if (await WithoutSubscription(userId, cancellationToken) is { } refused)
            return refused;

        var link = await dbContext.CalendarFeedLinks.FirstOrDefaultAsync(cancellationToken);
        if (link is null)
            return await TurnOn(cancellationToken);

        link.Reset(time.GetUtcNow());
        await dbContext.SaveChangesAsync(cancellationToken);
        logger.LogInformation("User {UserId} reset the calendar feed's link", userId);
        return Response(link);
    }

    /// <summary>Off: the link names nothing any more. Turned on again later, it is another link.</summary>
    public async Task<OneOf<ApplicationError, Success>> TurnOff(CancellationToken cancellationToken)
    {
        if (currentUser.UserId is not { } userId)
            return new ApplicationError(ErrorTypes.Unauthorized, "User must be authenticated.");

        // Removed through SaveChanges, not ExecuteDelete: the account's other apps are told (TasksDbContext).
        var link = await dbContext.CalendarFeedLinks.FirstOrDefaultAsync(cancellationToken);
        if (link is null)
            return new Success();

        dbContext.CalendarFeedLinks.Remove(link);
        await dbContext.SaveChangesAsync(cancellationToken);
        logger.LogInformation("User {UserId} turned the calendar feed off", userId);
        return new Success();
    }

    /// <summary>
    /// The link a secret names, for the feed's own address, where nobody is signed in: looked up across accounts, as
    /// a job would. A secret this server did not sign is refused before the database is asked anything.
    /// </summary>
    public async Task<CalendarFeedLink?> FindBySecret(string? secret, CancellationToken cancellationToken) =>
        links.TryRead(secret, out var nonce)
            ? await dbContext.CalendarFeedLinks.IgnoreQueryFilters().AsNoTracking().FirstOrDefaultAsync(l => l.Nonce == nonce, cancellationToken)
            : null;

    /// <summary>
    /// A calendar just read the current user's feed (its request reads as the link's owner). Kept to the minute: a
    /// calendar reading every few seconds does not write every few seconds. No SaveChanges: nothing a screen shows
    /// moved enough to make every app of the account load again.
    /// </summary>
    public Task Fetched(DateTimeOffset now, CancellationToken cancellationToken)
    {
        var aMinuteAgo = now.AddMinutes(-1);
        return dbContext
            .CalendarFeedLinks.Where(l => l.LastFetchedAt == null || l.LastFetchedAt < aMinuteAgo)
            .ExecuteUpdateAsync(set => set.SetProperty(l => l.LastFetchedAt, now), cancellationToken);
    }

    private CalendarFeedResponse Response(CalendarFeedLink? link) =>
        link is null
            ? new CalendarFeedResponse(false)
            // The public address of this API, the one emailed links are built on.
            : new CalendarFeedResponse(true, addresses.Value.LinkBaseUrl.TrimEnd('/') + links.PathOf(link.Nonce), link.CreatedAt, link.LastFetchedAt);
}
