using Kadans.Modules.Tasks.Domain;
using Kadans.SharedKernel.Notifications;
using Kadans.SharedKernel.Users;

namespace Kadans.Modules.Tasks.Features.Reminders;

/// <summary>
/// What a reminder says, in the account's language and time zone: the same words whether the server pushes it or a
/// phone rings it from its window (ARCHITECTURE → "Reminders ring on the phone").
/// </summary>
internal static class ReminderNotification
{
    public const string Kind = "occurrence.due";

    /// <summary>The todo's title, and "Starts at 08:00 — in 15 min" as it reads at <paramref name="at"/>.</summary>
    public static (string Title, string Body) Texts(TodoOccurrence occurrence, UserSummary? user, DateTimeOffset at)
    {
        var timeZone = user is not null && TimeZoneInfo.TryFindSystemTimeZoneById(user.TimeZoneId, out var found) ? found : TimeZoneInfo.Utc;
        var texts = LocalizedTexts.Reminder(user?.Language ?? "en");
        var local = TimeZoneInfo.ConvertTime(occurrence.ScheduledAt, timeZone);
        var untilStart = occurrence.ScheduledAt - at;

        var body = untilStart > TimeSpan.FromSeconds(30)
            ? string.Format(texts.StartsAtFormat, $"{local:HH:mm}", Describe(untilStart, texts))
            : string.Format(texts.StartsNowFormat, $"{local:HH:mm}");
        return (occurrence.Todo!.Title, body);
    }

    /// <summary>The reminder as the server sends it at <paramref name="now"/>, marked so the push can skip phones that have it.</summary>
    public static NotificationMessage Message(TodoOccurrence occurrence, UserSummary? user, DateTimeOffset now, long accountVersion)
    {
        var todo = occurrence.Todo!;
        var (title, body) = Texts(occurrence, user, now);
        return new NotificationMessage(
            Kind,
            title,
            body,
            new Dictionary<string, string>
            {
                ["todoId"] = todo.Id.ToString(),
                ["occurrenceId"] = occurrence.Id.ToString(),
                ["scheduledAt"] = occurrence.ScheduledAt.ToString("O"),
                ["notifyAt"] = occurrence.NotifyAt?.ToString("O") ?? string.Empty,
                ["pomodoroTemplateId"] = todo.PomodoroTemplateId?.ToString() ?? string.Empty,
            },
            new ReminderDelivery(occurrence.Id, occurrence.NotifyAt ?? now, occurrence.ScheduledAt, accountVersion)
        );
    }

    /// <summary>
    /// "15 min", "2 h 05 min", "3 d 4 h" — unit words from the user's language. Rounded up to the minute: the job
    /// runs a few seconds after the notify time, when a 15-minute lead is 14 min 5x s away, and must still read
    /// "15 min" (and an hour "1 h", not "59 min").
    /// </summary>
    internal static string Describe(TimeSpan span, ReminderTexts texts)
    {
        const long minutesPerDay = 24 * 60;
        var minutes = Math.Max(1L, (long)Math.Ceiling(span.TotalMinutes));
        if (minutes < 60)
            return $"{minutes} {texts.MinuteAbbrev}";
        if (minutes < minutesPerDay)
            return minutes % 60 == 0
                ? $"{minutes / 60} {texts.HourAbbrev}"
                : $"{minutes / 60} {texts.HourAbbrev} {minutes % 60:00} {texts.MinuteAbbrev}";
        var hours = minutes % minutesPerDay / 60;
        return hours == 0
            ? $"{minutes / minutesPerDay} {texts.DayAbbrev}"
            : $"{minutes / minutesPerDay} {texts.DayAbbrev} {hours} {texts.HourAbbrev}";
    }
}
