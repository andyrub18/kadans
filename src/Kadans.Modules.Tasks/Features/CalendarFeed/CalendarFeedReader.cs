using Kadans.Modules.Tasks.Domain;
using Kadans.Modules.Tasks.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using TaskStatus = Kadans.Modules.Tasks.Domain.TaskStatus;

namespace Kadans.Modules.Tasks.Features.CalendarFeed;

/// <summary>
/// The current user's todos as a calendar file: what their own calendar screen shows, pending times and done ones
/// (never cancelled ones), from some days back to some months ahead, and past the stored occurrences the times
/// their rules give. Titles and times only: whoever holds the link reads the file, so a description or a note
/// never goes in it. Read through the usual filters, the request's user being the link's owner.
/// </summary>
internal sealed class CalendarFeedReader(TasksDbContext dbContext, IOptions<TasksOptions> options)
{
    /// <summary>The calendar's name in the calendar app; the same in the three languages.</summary>
    public const string CalendarName = "Kadans";

    private sealed record Row(
        Guid TodoId,
        string Title,
        DateTimeOffset OriginalScheduledAt,
        DateTimeOffset ScheduledAt,
        OccurrenceStatus Status,
        DateTimeOffset? CompletedAt,
        DateTimeOffset? RescheduledAt,
        DateTimeOffset TodoUpdatedAt,
        Guid? PomodoroTemplateId
    );

    /// <summary>The calendar with nothing in it: what an account without the phones' subscription gives its calendars.</summary>
    public string Empty() => CalendarFeedWriter.Write([], CalendarName, options.Value.CalendarFeedRefreshMinutes);

    public async Task<string> Read(DateTimeOffset now, CancellationToken cancellationToken)
    {
        var settings = options.Value;
        // Whole days: the window moves once a day, not at every read, so an unchanged account reads the same file.
        var today = new DateTimeOffset(now.UtcDateTime.Date, TimeSpan.Zero);
        var from = today.AddDays(-Math.Max(0, settings.CalendarFeedPastDays));
        var to = today.AddDays(Math.Max(1, settings.CalendarFeedAheadDays) + 1);
        var max = Math.Max(1, settings.CalendarFeedMaxEvents);

        // Done times of any todo, finished or not; pending ones of a todo still going (a cancelled todo keeps its
        // missed times pending, and they are no longer anyone's plan).
        var stored = dbContext
            .TodoOccurrences.IgnoreQueryFilters([TasksDbContext.ACTIVE_OCCURRENCES_FILTER, TasksDbContext.ACTIVE_TODOS_FILTER])
            .AsNoTracking()
            .Where(o =>
                o.Status == OccurrenceStatus.Completed
                || (o.Status == OccurrenceStatus.Pending && (o.Todo!.Status == TaskStatus.Scheduled || o.Todo.Status == TaskStatus.Started))
            );

        // The nearest first on each side: a reminder every five minutes stores thousands of rows a month.
        var ahead = await Rows(stored.Where(o => o.ScheduledAt >= now && o.ScheduledAt <= to).OrderBy(o => o.ScheduledAt).Take(max))
            .ToListAsync(cancellationToken);
        var past = await Rows(stored.Where(o => o.ScheduledAt >= from && o.ScheduledAt < now).OrderByDescending(o => o.ScheduledAt).Take(max))
            .ToListAsync(cancellationToken);

        var focusMinutes = await dbContext
            .PomodoroTemplates.AsNoTracking()
            .Select(t => new { t.Id, Minutes = t.Phases.Sum(p => p.DurationMinutes) })
            .ToDictionaryAsync(t => t.Id, t => t.Minutes, cancellationToken);
        int? MinutesOf(Guid? templateId) => templateId is { } id && focusMinutes.TryGetValue(id, out var minutes) && minutes > 0 ? minutes : null;

        var occurrences = new List<FeedOccurrence>(ahead.Count + past.Count);
        foreach (var row in ahead.Concat(past))
        {
            occurrences.Add(
                new FeedOccurrence(
                    row.TodoId,
                    row.Title,
                    row.OriginalScheduledAt,
                    row.ScheduledAt,
                    row.Status == OccurrenceStatus.Completed,
                    MinutesOf(row.PomodoroTemplateId),
                    Latest(row.TodoUpdatedAt, row.CompletedAt, row.RescheduledAt)
                )
            );
        }

        // Past the stored occurrences: the times the rules give, as the calendar screen previews them.
        var open = await dbContext
            .Todos.AsNoTracking()
            .Include(t => t.RecurrenceRule)
            .Where(t => t.OccurrencesGeneratedThrough == null || t.OccurrencesGeneratedThrough < to)
            .ToListAsync(cancellationToken);
        var keep = Math.Min(max, Math.Max(1, settings.MaxPreviewPerTodo));
        foreach (var todo in open)
        {
            var through = todo.OccurrencesGeneratedThrough;
            var previewFrom = through is null || through < from ? from : through.Value;
            // One more than kept: the time at "through" (already a row) comes back first and is dropped.
            var previews = todo.RecurrenceRule.GetOccurrences(previewFrom, to, limit: keep + 1).Where(at => through is null || at > through.Value).Take(keep);
            foreach (var at in previews)
                occurrences.Add(new FeedOccurrence(todo.Id, todo.Title, at, at, Done: false, MinutesOf(todo.PomodoroTemplateId), todo.UpdatedAt));
        }

        return CalendarFeedWriter.Write(CalendarFeedEvents.Build(occurrences, now, max), CalendarName, settings.CalendarFeedRefreshMinutes);
    }

    private static IQueryable<Row> Rows(IQueryable<TodoOccurrence> occurrences) =>
        occurrences.Select(o => new Row(
            o.TodoId,
            o.Todo!.Title,
            o.OriginalScheduledAt,
            o.ScheduledAt,
            o.Status,
            o.CompletedAt,
            o.RescheduledAt,
            o.Todo.UpdatedAt,
            o.Todo.PomodoroTemplateId
        ));

    private static DateTimeOffset Latest(DateTimeOffset first, params DateTimeOffset?[] others)
    {
        var latest = first;
        foreach (var other in others)
        {
            if (other > latest)
                latest = other.Value;
        }
        return latest;
    }
}
