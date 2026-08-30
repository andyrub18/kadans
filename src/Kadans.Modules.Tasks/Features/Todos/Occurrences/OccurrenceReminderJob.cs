using Kadans.Modules.Tasks.Domain;
using Kadans.Modules.Tasks.Persistence;
using Kadans.SharedKernel.Notifications;
using Kadans.SharedKernel.Users;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Quartz;

namespace Kadans.Modules.Tasks.Features.Todos.Occurrences;

/// <summary>
/// Sends the "starts soon" reminder for pending occurrences whose <c>NotifyAt</c> has passed,
/// once each (<c>NotifiedAt</c>). Occurrences that are already long past are skipped silently
/// rather than delivered late.
/// </summary>
[DisallowConcurrentExecution]
internal sealed class OccurrenceReminderJob(
    TasksDbContext dbContext,
    INotificationDispatcher dispatcher,
    IUserDirectory users,
    IOptions<TasksOptions> options,
    ILogger<OccurrenceReminderJob> logger
) : IJob
{
    public static readonly JobKey Key = new("occurrence-reminder", "tasks");
    public const string Kind = "occurrence.due";

    private const int BatchSize = 500;

    public async Task Execute(IJobExecutionContext context)
    {
        var cancellationToken = context.CancellationToken;
        var now = DateTimeOffset.UtcNow;
        var staleBefore = now.AddMinutes(-options.Value.ReminderStaleAfterMinutes);

        // Too late to be useful: stamp them so the index stops returning them.
        await dbContext
            .TodoOccurrences.IgnoreQueryFilters()
            .Where(o => o.Status == OccurrenceStatus.Pending && o.NotifiedAt == null && o.NotifyAt != null && o.NotifyAt <= now && o.ScheduledAt < staleBefore)
            .ExecuteUpdateAsync(s => s.SetProperty(o => o.NotifiedAt, now), cancellationToken);

        var due = await dbContext
            .TodoOccurrences.IgnoreQueryFilters()
            .Include(o => o.Todo)
            .Where(o => o.Status == OccurrenceStatus.Pending && o.NotifiedAt == null && o.NotifyAt != null && o.NotifyAt <= now)
            .OrderBy(o => o.NotifyAt)
            .Take(BatchSize)
            .ToListAsync(cancellationToken);

        if (due.Count == 0)
            return;

        var timeZones = new Dictionary<string, TimeZoneInfo>();
        foreach (var occurrence in due)
        {
            var todo = occurrence.Todo!;
            var timeZone = await TimeZoneForAsync(todo.UserId, timeZones, cancellationToken);
            var local = TimeZoneInfo.ConvertTime(occurrence.ScheduledAt, timeZone);
            var untilStart = occurrence.ScheduledAt - now;

            var message = new NotificationMessage(
                Kind,
                todo.Title,
                untilStart > TimeSpan.FromSeconds(30)
                    ? $"Starts at {local:HH:mm} — in {Describe(untilStart)}"
                    : $"Starts now ({local:HH:mm})",
                new Dictionary<string, string>
                {
                    ["todoId"] = todo.Id.ToString(),
                    ["occurrenceId"] = occurrence.Id.ToString(),
                    ["scheduledAt"] = occurrence.ScheduledAt.ToString("O"),
                    ["pomodoroTemplateId"] = todo.PomodoroTemplateId?.ToString() ?? string.Empty,
                }
            );

            await dispatcher.DispatchAsync(todo.UserId, message, cancellationToken);
            occurrence.NotifiedAt = now;
        }

        await dbContext.SaveChangesAsync(cancellationToken);
        logger.LogInformation("Reminder run: {Count} reminder(s) sent", due.Count);
    }

    /// <summary>"15 min", "2 h 05 min", "3 d 4 h".</summary>
    internal static string Describe(TimeSpan span)
    {
        if (span.TotalMinutes < 1)
            return "less than a minute";
        if (span.TotalHours < 1)
            return $"{(int)span.TotalMinutes} min";
        if (span.TotalDays < 1)
            return span.Minutes == 0 ? $"{(int)span.TotalHours} h" : $"{(int)span.TotalHours} h {span.Minutes:00} min";
        return span.Hours == 0 ? $"{(int)span.TotalDays} d" : $"{(int)span.TotalDays} d {span.Hours} h";
    }

    private async Task<TimeZoneInfo> TimeZoneForAsync(string userId, Dictionary<string, TimeZoneInfo> cache, CancellationToken cancellationToken)
    {
        if (cache.TryGetValue(userId, out var cached))
            return cached;

        var user = await users.FindAsync(userId, cancellationToken);
        var timeZone = user is not null && TimeZoneInfo.TryFindSystemTimeZoneById(user.TimeZoneId, out var found) ? found : TimeZoneInfo.Utc;
        cache[userId] = timeZone;
        return timeZone;
    }
}
