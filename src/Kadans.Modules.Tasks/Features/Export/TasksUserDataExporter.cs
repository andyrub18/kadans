using System.Text;
using Kadans.Modules.Tasks.Domain;
using Kadans.Modules.Tasks.Features.CalendarFeed;
using Kadans.Modules.Tasks.Persistence;
using Kadans.SharedKernel.Security;
using Kadans.SharedKernel.Text;
using Kadans.SharedKernel.Users;
using Microsoft.EntityFrameworkCore;

namespace Kadans.Modules.Tasks.Features.Export;

/// <summary>
/// "Export my data": the todos and the focus time (ARCHITECTURE → "Statements in, data out").
/// <list type="bullet">
/// <item><c>todos/todos.ics</c>, a calendar file any calendar app opens: every time done so far and the times to come
/// over a year, written as the calendar feed writes them (a todo's rule shows as its times).</item>
/// <item><c>todos/todos.csv</c>, what the person wrote: each todo's title, description, state, and how it repeats.</item>
/// <item><c>todos/history.csv</c>, what became of their times: done, cancelled or moved, with the person's notes and
/// reasons, which the calendar file leaves out.</item>
/// <item><c>focus/sessions.csv</c>, each focus session and the minutes of focus in it; <c>focus/cycles.csv</c>, the
/// person's own focus cycles.</item>
/// </list>
/// Unlike the feed, it is not part of the phones' subscription and needs no link: the person is signed in and the
/// data is theirs. It is read as the request's user, which the endpoint guarantees is the one asked for. Times are the
/// person's own (their account's time zone); headers are English in every language, for whatever reads the file next.
/// </summary>
internal sealed class TasksUserDataExporter(TasksDbContext dbContext, CalendarFeedReader calendar, ICurrentUserService currentUser, IUserDirectory users)
    : IUserDataExporter
{
    /// <summary>Ten years back covers every time ever done; a year ahead is as far as a plan is worth carrying off.</summary>
    internal const int PastDays = 3660;
    internal const int AheadDays = 366;

    /// <summary>The newest and nearest are kept when an account has more: a reminder every five minutes makes thousands a month.</summary>
    internal const int MaxEvents = 20_000;

    /// <summary>The newest lines of the history and the newest sessions, when an account has more.</summary>
    internal const int MaxHistory = 50_000;
    internal const int MaxSessions = 20_000;

    public async Task<IReadOnlyList<ExportedFile>> ExportAsync(string userId, CancellationToken cancellationToken = default)
    {
        if (currentUser.UserId != userId)
            throw new InvalidOperationException("The todos are exported by their owner's own request.");

        var user = await users.FindAsync(userId, cancellationToken);
        var zone = user is not null && TimeZoneInfo.TryFindSystemTimeZoneById(user.TimeZoneId, out var found) ? found : TimeZoneInfo.Utc;
        string Moment(DateTimeOffset? at) => at is { } value ? TimeZoneInfo.ConvertTime(value, zone).ToString("yyyy-MM-dd HH:mm") : string.Empty;

        var ics = await calendar.Read(DateTimeOffset.UtcNow, PastDays, AheadDays, MaxEvents, cancellationToken);

        var todos = await dbContext
            .Todos.IgnoreQueryFilters([TasksDbContext.ACTIVE_TODOS_FILTER])
            .AsNoTracking()
            .OrderBy(t => t.CreatedAt)
            .Select(t => new { t.Title, t.Description, t.Status, Rule = t.RecurrenceRule.Rrule, t.RecurrenceRule.TimeZoneId, t.CreatedAt })
            .ToListAsync(cancellationToken);
        var todosFile = new Csv("Title", "Description", "Status", "Repeats (RRULE)", "Time zone", "Created");
        foreach (var todo in todos)
            todosFile.Row(todo.Title, todo.Description, todo.Status.ToString(), todo.Rule, todo.TimeZoneId, todo.CreatedAt.UtcDateTime.ToString("yyyy-MM-dd"));

        // Every time something happened to: done, cancelled, moved, or given a note. A time still to come as planned
        // is in the calendar file, and says nothing more here. Of every todo, the cancelled ones too.
        var history = await dbContext
            .TodoOccurrences.IgnoreQueryFilters([TasksDbContext.ACTIVE_OCCURRENCES_FILTER, TasksDbContext.ACTIVE_TODOS_FILTER])
            .AsNoTracking()
            .Where(o => o.Status != OccurrenceStatus.Pending || o.Remarks != null || o.RescheduledAt != null)
            .OrderByDescending(o => o.ScheduledAt)
            .Take(MaxHistory)
            .Select(o => new
            {
                o.Todo!.Title, o.OriginalScheduledAt, o.ScheduledAt, o.Status, o.CompletedAt, o.CancelledAt,
                o.Remarks, o.RescheduleReason, o.CancellationReason,
            })
            .ToListAsync(cancellationToken);
        var historyFile = new Csv("Todo", "Planned for", "Moved to", "Status", "Done at", "Cancelled at", "Note", "Reason");
        foreach (var time in history.AsEnumerable().Reverse())
            historyFile.Row(
                time.Title,
                Moment(time.OriginalScheduledAt),
                time.ScheduledAt != time.OriginalScheduledAt ? Moment(time.ScheduledAt) : null,
                time.Status.ToString(),
                Moment(time.CompletedAt),
                Moment(time.CancelledAt),
                time.Remarks,
                time.CancellationReason ?? time.RescheduleReason
            );

        var sessions = await dbContext
            .PomodoroRuns.IgnoreQueryFilters([TasksDbContext.ACTIVE_TODOS_FILTER])
            .AsNoTracking()
            .OrderByDescending(r => r.StartedAt)
            .Take(MaxSessions)
            .Select(r => new
            {
                r.Todo.Title, r.StartedAt, r.CompletedAt, r.Status,
                Focus = r.Phases.Where(p => p.Type == PomodoroPhaseType.Focus).Select(p => new { p.StartedAt, p.CompletedAt, p.PausedSeconds }).ToList(),
            })
            .AsSplitQuery()
            .ToListAsync(cancellationToken);
        var sessionsFile = new Csv("Todo", "Started", "Ended", "Status", "Focus minutes");
        foreach (var session in sessions.AsEnumerable().Reverse())
            sessionsFile.Row(
                session.Title,
                Moment(session.StartedAt),
                Moment(session.CompletedAt),
                session.Status.ToString(),
                // As the stats count it: the time each focus phase really took, its pauses left out.
                (session.Focus.Sum(p => PomodoroRunPhase.SecondsSpent(p.StartedAt, p.CompletedAt, p.PausedSeconds) ?? 0) / 60).ToString()
            );

        var cycles = await dbContext
            .PomodoroTemplates.AsNoTracking()
            .OrderBy(t => t.CreatedAt)
            .Select(t => new { t.Name, Phases = t.Phases.OrderBy(p => p.Order).Select(p => new { p.Type, p.DurationMinutes }).ToList() })
            .AsSplitQuery()
            .ToListAsync(cancellationToken);
        var cyclesFile = new Csv("Cycle", "Phases (minutes)");
        foreach (var cycle in cycles)
            cyclesFile.Row(cycle.Name, string.Join(", ", cycle.Phases.Select(p => $"{p.Type} {p.DurationMinutes}")));

        return
        [
            new ExportedFile("todos/todos.ics", Encoding.UTF8.GetBytes(ics)),
            new ExportedFile("todos/todos.csv", todosFile.ToBytes()),
            new ExportedFile("todos/history.csv", historyFile.ToBytes()),
            new ExportedFile("focus/sessions.csv", sessionsFile.ToBytes()),
            new ExportedFile("focus/cycles.csv", cyclesFile.ToBytes()),
        ];
    }
}
