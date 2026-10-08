using Kadans.Modules.Tasks.Contracts;
using Kadans.Modules.Tasks.Domain;
using Kadans.Modules.Tasks.Persistence;
using Kadans.SharedKernel.Errors;
using Kadans.SharedKernel.Http;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using OneOf;
using TaskStatus = Kadans.Modules.Tasks.Domain.TaskStatus;

namespace Kadans.Modules.Tasks.Features.Todos;

internal sealed class GetTodos(TasksDbContext dbContext, IOptions<TasksOptions> options, ILogger<GetTodos> logger)
{
    // Home's list and the calendar's two reads run behind almost every screen. Compiled once, they skip EF's
    // per-call work of turning LINQ into its cached SQL (docs/LOADTEST.md). Read only: no change tracking.
    private static readonly Func<TasksDbContext, TaskStatus?, int, int, IAsyncEnumerable<Todo>> TodosPage = EF.CompileAsyncQuery(
        (TasksDbContext db, TaskStatus? status, int skip, int take) => db
            .Todos.IgnoreQueryFilters(new[] { TasksDbContext.ACTIVE_TODOS_FILTER })
            .AsNoTracking()
            .Include(t => t.RecurrenceRule)
            .Include(t => t.Remarks)
            .Where(t => status == null || t.Status == status)
            .OrderByDescending(t => t.CreatedAt)
            .Skip(skip)
            .Take(take)
    );

    // Identity resolution builds each todo once for all its occurrences.
    private static readonly Func<TasksDbContext, DateTimeOffset, DateTimeOffset, IAsyncEnumerable<TodoOccurrence>> OccurrencesBetween =
        EF.CompileAsyncQuery(
            (TasksDbContext db, DateTimeOffset from, DateTimeOffset to) => db
                .TodoOccurrences.AsNoTrackingWithIdentityResolution()
                .Include(o => o.Todo)
                .Where(o => o.ScheduledAt >= from && o.ScheduledAt <= to)
        );

    private static readonly Func<TasksDbContext, DateTimeOffset, IAsyncEnumerable<Todo>> GeneratedShortOf = EF.CompileAsyncQuery(
        (TasksDbContext db, DateTimeOffset to) => db
            .Todos.AsNoTracking()
            .Include(t => t.RecurrenceRule)
            .Where(t => t.OccurrencesGeneratedThrough == null || t.OccurrencesGeneratedThrough < to)
    );

    public async Task<OneOf<ApplicationError, List<TodoResponse>>> GetAllTodos(int page, int pageSize, TaskStatus? status)
    {
        if (Paging.Check(page, pageSize) is { } pagingError)
            return pagingError;

        var todos = await TodosPage(dbContext, status, (page - 1) * pageSize, pageSize).ToListAsync();

        return todos.ConvertAll(t => t.ToResponse());
    }

    public async Task<OneOf<ApplicationError, TodoResponse>> GetTodoById(Guid id)
    {
        var todo = await dbContext
            .Todos.IgnoreQueryFilters([TasksDbContext.ACTIVE_TODOS_FILTER])
            .AsNoTracking()
            .Include(t => t.RecurrenceRule)
            .Include(t => t.Remarks)
            .FirstOrDefaultAsync(t => t.Id == id);

        if (todo is null)
            return new ApplicationError(ErrorTypes.TodoNotFound, $"Todo with id {id} not found");

        return todo.ToResponse();
    }

    /// <summary>Pending occurrences of one todo, soonest first.</summary>
    public async Task<OneOf<ApplicationError, List<TodoOccurrenceResponse>>> GetOccurrencesByTodoId(Guid todoId, int page = 1, int pageSize = 20)
    {
        if (Paging.Check(page, pageSize) is { } pagingError)
            return pagingError;

        // The Todo navigation carries the active-todos filter; a finished todo must still list its rows.
        var occurrences = await dbContext
            .TodoOccurrences.IgnoreQueryFilters([TasksDbContext.ACTIVE_TODOS_FILTER])
            .AsNoTrackingWithIdentityResolution()
            .Include(o => o.Todo)
            .Where(o => o.TodoId == todoId)
            .OrderBy(o => o.ScheduledAt)
            .Skip((page - 1) * pageSize)
            .Take(pageSize)
            .ToListAsync();

        return occurrences.ConvertAll(o => o.ToResponse());
    }

    /// <summary>The widest window one calendar request may ask for; the app asks for six weeks at a time.</summary>
    internal static readonly TimeSpan MaxWindow = TimeSpan.FromDays(366);

    /// <summary>
    /// Pending occurrences across all todos in a window of up to a year. Past the materialization
    /// horizon the window is filled with computed previews, so calendars can page far ahead.
    /// </summary>
    public async Task<OneOf<ApplicationError, List<TodoOccurrenceResponse>>> GetOccurrencesByDateRange(DateTimeOffset from, DateTimeOffset to)
    {
        if (from > to)
            return new ApplicationError(ErrorTypes.InvalidInterval, "Start date must be earlier than end date.");

        if (to - from > MaxWindow)
            return new ApplicationError(ErrorTypes.InvalidInterval, "The calendar range can be at most a year.");

        var materialized = await OccurrencesBetween(dbContext, from, to).ToListAsync();
        var result = materialized.ConvertAll(o => o.ToResponse());

        var notFullyGenerated = await GeneratedShortOf(dbContext, to).ToListAsync();

        foreach (var todo in notFullyGenerated)
        {
            var generatedThrough = todo.OccurrencesGeneratedThrough;
            var previewFrom = generatedThrough is null || generatedThrough < from ? from : generatedThrough.Value;

            // One more than kept: the instance at generatedThrough (already a row) comes back first and is dropped.
            var previews = todo
                .RecurrenceRule.GetOccurrences(previewFrom, to, limit: options.Value.MaxPreviewPerTodo + 1)
                .Where(at => generatedThrough is null || at > generatedThrough.Value)
                .Take(options.Value.MaxPreviewPerTodo)
                .Select(todo.PreviewOccurrence);

            result.AddRange(previews);
        }

        result.Sort((a, b) => a.ScheduledAt.CompareTo(b.ScheduledAt));
        return result;
    }

    /// <summary>Every occurrence of a todo, any status, newest first.</summary>
    public async Task<OneOf<ApplicationError, List<TodoOccurrenceResponse>>> GetTodoHistory(Guid todoId, int page = 1, int pageSize = 20)
    {
        if (Paging.Check(page, pageSize) is { } pagingError)
            return pagingError;

        var occurrences = await dbContext
            .TodoOccurrences.IgnoreQueryFilters([TasksDbContext.ACTIVE_OCCURRENCES_FILTER, TasksDbContext.ACTIVE_TODOS_FILTER])
            .AsNoTrackingWithIdentityResolution()
            .Include(o => o.Todo)
            .Where(o => o.TodoId == todoId)
            .OrderByDescending(o => o.ScheduledAt)
            .Skip((page - 1) * pageSize)
            .Take(pageSize)
            .ToListAsync();

        logger.LogDebug("History for todo {TodoId}: {Count} row(s)", todoId, occurrences.Count);
        return occurrences.ConvertAll(o => o.ToResponse());
    }
}
