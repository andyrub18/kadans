using Kadans.Modules.Tasks.Contracts;
using Kadans.Modules.Tasks.Domain;
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
            // Pending, of the account's active todos (the query filters), with a reminder still to ring: the one before
            // the start, or the start itself (ReminderMoments).
            due = await dbContext
                .TodoOccurrences.AsNoTracking()
                .Include(o => o.Todo)
                .Where(o => o.NotifyAt != null && o.NotifyAt <= through && o.ScheduledAt > now)
                .OrderBy(o => o.NotifyAt)
                .Take(MaxReminders)
                .ToListAsync(cancellationToken);
            await snapshot.CommitAsync(cancellationToken);
        }

        var (moments, reach) = ReminderMoments.Window(due, o => o.NotifyAt, o => o.ScheduledAt, now, through, cut: due.Count == MaxReminders, MaxReminders);
        through = reach;

        var user = (await users.FindManyAsync([userId], cancellationToken)).GetValueOrDefault(userId);
        var reminders = moments.ConvertAll(r =>
        {
            var (title, body) = ReminderNotification.Texts(r.Occurrence, user, r.At);
            return new UpcomingReminder(r.Occurrence.Id, r.Occurrence.TodoId, title, body, r.At, r.Occurrence.ScheduledAt, r.Occurrence.Todo!.PomodoroTemplateId);
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
    /// Asked by a device as its alarm rings, when it is online: does the reminder at <paramref name="at"/> still ring?
    /// Not when its occurrence was deleted, done, cancelled or moved elsewhere since the window was fetched (that time
    /// is no longer one of its reminders), nor while a focus session on the todo runs: the person has begun. Without a
    /// time (apps from before the "starts now" reminder): whether the occurrence still has a reminder.
    /// </summary>
    public async Task<ReminderCheckResponse> Check(Guid occurrenceId, DateTimeOffset? at, CancellationToken cancellationToken)
    {
        var occurrence = await dbContext
            .TodoOccurrences.AsNoTracking()
            .Where(o => o.Id == occurrenceId)
            .Select(o => new
            {
                o.TodoId,
                o.NotifyAt,
                o.ScheduledAt,
            })
            .FirstOrDefaultAsync(cancellationToken);
        if (occurrence?.NotifyAt is not { } notifyAt)
            return new ReminderCheckResponse(occurrenceId, false, null);
        if (at is not { } moment)
            return new ReminderCheckResponse(occurrenceId, true, notifyAt, occurrence.ScheduledAt);

        var due =
            ReminderMoments.Includes(ReminderMoments.Of(notifyAt, occurrence.ScheduledAt), moment)
            && !await dbContext.PomodoroRuns.AnyAsync(
                r => r.TodoId == occurrence.TodoId && (r.Status == PomodoroRunStatus.Active || r.Status == PomodoroRunStatus.Paused),
                cancellationToken
            );
        return new ReminderCheckResponse(occurrenceId, due, notifyAt, occurrence.ScheduledAt);
    }
}
