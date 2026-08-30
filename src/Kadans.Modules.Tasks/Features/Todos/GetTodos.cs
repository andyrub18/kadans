using Kadans.Modules.Tasks.Contracts;
using Kadans.Modules.Tasks.Persistence;
using Kadans.SharedKernel.Errors;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using OneOf;
using TaskStatus = Kadans.Modules.Tasks.Domain.TaskStatus;

namespace Kadans.Modules.Tasks.Features.Todos;

internal sealed class GetTodos(TasksDbContext dbContext, IOptions<TasksOptions> options, ILogger<GetTodos> logger)
{
    public async Task<OneOf<ApplicationError, List<TodoResponse>>> GetAllTodos(int page, int pageSize, TaskStatus? status)
    {
        var todos = await dbContext
            .Todos.IgnoreQueryFilters([TasksDbContext.ACTIVE_TODOS_FILTER])
            .Include(t => t.RecurrenceRule)
            .Include(t => t.Remarks)
            .Where(t => status == null || t.Status == status)
            .OrderByDescending(t => t.CreatedAt)
            .Skip((page - 1) * pageSize)
            .Take(pageSize)
            .ToListAsync();

        return todos.ConvertAll(t => t.ToResponse());
    }

    public async Task<OneOf<ApplicationError, TodoResponse>> GetTodoById(Guid id)
    {
        var todo = await dbContext
            .Todos.IgnoreQueryFilters([TasksDbContext.ACTIVE_TODOS_FILTER])
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
        // The Todo navigation carries the active-todos filter; a finished todo must still list its rows.
        var occurrences = await dbContext
            .TodoOccurrences.IgnoreQueryFilters([TasksDbContext.ACTIVE_TODOS_FILTER])
            .Include(o => o.Todo)
            .Where(o => o.TodoId == todoId)
            .OrderBy(o => o.ScheduledAt)
            .Skip((page - 1) * pageSize)
            .Take(pageSize)
            .ToListAsync();

        return occurrences.ConvertAll(o => o.ToResponse());
    }

    /// <summary>
    /// Pending occurrences across all todos in a window. Past the materialization horizon the
    /// window is filled with computed previews so calendars can look arbitrarily far ahead.
    /// </summary>
    public async Task<OneOf<ApplicationError, List<TodoOccurrenceResponse>>> GetOccurrencesByDateRange(DateTimeOffset from, DateTimeOffset to)
    {
        if (from > to)
            return new ApplicationError(ErrorTypes.InvalidInterval, "Start date must be earlier than end date.");

        var materialized = await dbContext
            .TodoOccurrences.Include(o => o.Todo)
            .Where(o => o.ScheduledAt >= from && o.ScheduledAt <= to)
            .ToListAsync();

        var result = materialized.ConvertAll(o => o.ToResponse());

        var notFullyGenerated = await dbContext
            .Todos.Include(t => t.RecurrenceRule)
            .Where(t => t.OccurrencesGeneratedThrough == null || t.OccurrencesGeneratedThrough < to)
            .ToListAsync();

        foreach (var todo in notFullyGenerated)
        {
            var generatedThrough = todo.OccurrencesGeneratedThrough;
            var previewFrom = generatedThrough is null || generatedThrough < from ? from : generatedThrough.Value;

            var previews = todo
                .RecurrenceRule.GetOccurrences(previewFrom, to)
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
        var occurrences = await dbContext
            .TodoOccurrences.IgnoreQueryFilters([TasksDbContext.ACTIVE_OCCURRENCES_FILTER, TasksDbContext.ACTIVE_TODOS_FILTER])
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
