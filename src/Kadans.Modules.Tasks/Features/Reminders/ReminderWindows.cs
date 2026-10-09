using Kadans.Modules.Tasks.Contracts;
using Kadans.Modules.Tasks.Persistence;
using Kadans.SharedKernel.Errors;
using Kadans.SharedKernel.Security;
using Kadans.SharedKernel.Users;
using System.Data;
using Microsoft.EntityFrameworkCore;
using OneOf;
using OneOf.Types;

namespace Kadans.Modules.Tasks.Features.Reminders;

/// <summary>
/// The reminders a device rings itself (ARCHITECTURE → "Reminders ring on the phone"): its window of the next days,
/// written as the push would write them, and recorded on the device so the push skips what it has.
/// </summary>
internal sealed class ReminderWindows(
    TasksDbContext dbContext,
    IDeviceReminders devices,
    IMobileAccess mobileAccess,
    IUserDirectory users,
    ICurrentUserService currentUser,
    TimeProvider time
)
{
    public const int MaxDays = 7;

    /// <summary>More than any person schedules in a week; Android holds 500 alarms per app.</summary>
    public const int MaxReminders = 400;

    public async Task<OneOf<ApplicationError, ReminderWindowResponse>> Sync(ReminderSyncRequest request, CancellationToken cancellationToken)
    {
        if (currentUser.UserId is not { } userId)
            return new ApplicationError(ErrorTypes.Unauthorized, "User must be authenticated.");

        var platform = await devices.PlatformOfAsync(userId, request.InstallationId, cancellationToken);
        if (platform is null)
            return new ApplicationError(ErrorTypes.DeviceNotFound, $"Device {request.InstallationId} not found.");

        var now = time.GetUtcNow();
        // A phone rings reminders for an account with a subscription only, as the push does (IMobileAccess).
        if (platform is "Android" or "Ios" && !await mobileAccess.AllowsPhonesAsync(userId, cancellationToken))
            return new ReminderWindowResponse(now, now, []);

        var through = now.AddDays(Math.Clamp(request.Days ?? MaxDays, 1, MaxDays));
        // The version and the reminders from one snapshot: the window holds that version's changes, no more, no less.
        long version;
        List<Domain.TodoOccurrence> due;
        await using (var snapshot = await dbContext.Database.BeginTransactionAsync(IsolationLevel.RepeatableRead, cancellationToken))
        {
            version = await dbContext
                .ReminderChanges.AsNoTracking()
                .Where(c => c.UserId == userId)
                .Select(c => c.Version)
                .FirstOrDefaultAsync(cancellationToken);
            // Pending, of the account's active todos (the query filters), still to ring.
            due = await dbContext
                .TodoOccurrences.AsNoTracking()
                .Include(o => o.Todo)
                .Where(o => o.NotifyAt != null && o.NotifyAt > now && o.NotifyAt <= through)
                .OrderBy(o => o.NotifyAt)
                .Take(MaxReminders)
                .ToListAsync(cancellationToken);
            await snapshot.CommitAsync(cancellationToken);
        }

        // Cut at the cap, the window reaches its last reminder only: the push covers what lies after it.
        if (due.Count == MaxReminders)
            through = due[^1].NotifyAt!.Value;

        var user = (await users.FindManyAsync([userId], cancellationToken)).GetValueOrDefault(userId);
        var reminders = due.ConvertAll(o =>
        {
            var (title, body) = ReminderNotification.Texts(o, user, o.NotifyAt!.Value);
            return new UpcomingReminder(o.Id, o.TodoId, title, body, o.NotifyAt.Value, o.ScheduledAt);
        });

        await devices.RecordSyncAsync(userId, request.InstallationId, now, through, version, cancellationToken);
        return new ReminderWindowResponse(now, through, reminders);
    }

    /// <summary>The device rings nothing itself any more: every reminder is pushed to it again.</summary>
    public async Task<OneOf<ApplicationError, Success>> Stop(Guid installationId, CancellationToken cancellationToken)
    {
        if (currentUser.UserId is not { } userId)
            return new ApplicationError(ErrorTypes.Unauthorized, "User must be authenticated.");

        await devices.StopAsync(userId, installationId, cancellationToken);
        return new Success();
    }

    /// <summary>
    /// Asked by a phone as an alarm rings, when it is online: is this reminder still due? Not when its occurrence was
    /// deleted, done, cancelled or moved elsewhere since the window was fetched.
    /// </summary>
    public async Task<ReminderCheckResponse> Check(Guid occurrenceId, CancellationToken cancellationToken)
    {
        var notifyAt = await dbContext
            .TodoOccurrences.AsNoTracking()
            .Where(o => o.Id == occurrenceId)
            .Select(o => o.NotifyAt)
            .FirstOrDefaultAsync(cancellationToken);
        return new ReminderCheckResponse(occurrenceId, notifyAt is not null, notifyAt);
    }
}
