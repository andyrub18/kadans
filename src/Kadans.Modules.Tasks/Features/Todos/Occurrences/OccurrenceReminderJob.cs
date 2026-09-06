using Kadans.Modules.Tasks.Domain;
using Kadans.Modules.Tasks.Features;
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

        var users = new Dictionary<string, (TimeZoneInfo Zone, string Language)>();
        foreach (var occurrence in due)
        {
            var todo = occurrence.Todo!;
            var (timeZone, language) = await UserContextAsync(todo.UserId, users, cancellationToken);
            var texts = LocalizedTexts.Reminder(language);
            var local = TimeZoneInfo.ConvertTime(occurrence.ScheduledAt, timeZone);
            var untilStart = occurrence.ScheduledAt - now;

            var message = new NotificationMessage(
                Kind,
                todo.Title,
                untilStart > TimeSpan.FromSeconds(30)
                    ? string.Format(texts.StartsAtFormat, $"{local:HH:mm}", Describe(untilStart, texts))
                    : string.Format(texts.StartsNowFormat, $"{local:HH:mm}"),
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

    /// <summary>"15 min", "2 h 05 min", "3 d 4 h" — unit words from the user's language.</summary>
    internal static string Describe(TimeSpan span, ReminderTexts texts)
    {
        if (span.TotalMinutes < 1)
            return texts.LessThanAMinute;
        if (span.TotalHours < 1)
            return $"{(int)span.TotalMinutes} {texts.MinuteAbbrev}";
        if (span.TotalDays < 1)
            return span.Minutes == 0
                ? $"{(int)span.TotalHours} {texts.HourAbbrev}"
                : $"{(int)span.TotalHours} {texts.HourAbbrev} {span.Minutes:00} {texts.MinuteAbbrev}";
        return span.Hours == 0
            ? $"{(int)span.TotalDays} {texts.DayAbbrev}"
            : $"{(int)span.TotalDays} {texts.DayAbbrev} {span.Hours} {texts.HourAbbrev}";
    }

    private async Task<(TimeZoneInfo, string)> UserContextAsync(string userId, Dictionary<string, (TimeZoneInfo, string)> cache, CancellationToken cancellationToken)
    {
        if (cache.TryGetValue(userId, out var cached))
            return cached;

        var user = await users.FindAsync(userId, cancellationToken);
        var timeZone = user is not null && TimeZoneInfo.TryFindSystemTimeZoneById(user.TimeZoneId, out var found) ? found : TimeZoneInfo.Utc;
        var context = (timeZone, user?.Language ?? "en");
        cache[userId] = context;
        return context;
    }
}
