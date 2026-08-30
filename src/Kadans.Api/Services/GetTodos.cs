using Kadans.Api.Contracts;
using Kadans.Api.Data;
using Kadans.SharedKernel.Errors;
using Microsoft.EntityFrameworkCore;
using OneOf;
using TaskStatus = Kadans.Api.Models.TaskStatus;

namespace Kadans.Api.Services;

public sealed class GetTodos(ApplicationDbContext dbContext, ILogger<GetTodos> logger)
{
    public async Task<OneOf<ApplicationError, List<TodoResponse>>> GetAllTodos(
        int page,
        int pageSize,
        TaskStatus? status
    )
    {
        try
        {
            var todos = await dbContext
                .Todos.IgnoreQueryFilters([ApplicationDbContext.ACTIVE_TODOS_FILTER])
                .Include(t => t.RecurrenceRule)
                .Include(t => t.Remarks)
                .Where(t => status == null || t.Status == status)
                .OrderByDescending(t => t.CreatedAt)
                .Skip((page - 1) * pageSize)
                .Take(pageSize)
                .ToListAsync();

            return todos.ConvertAll(t => t.ToResponse());
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "An error occurred while retrieving todos.");
            return new ApplicationError(
                ErrorTypes.DatabaseError,
                "An error occurred while retrieving todos."
            );
        }
    }

    public async Task<OneOf<ApplicationError, TodoResponse>> GetTodoById(Guid id)
    {
        try
        {
            var todo = await dbContext
                .Todos.Include(t => t.RecurrenceRule)
                .Include(t => t.Remarks)
                .FirstOrDefaultAsync(t => t.Id == id);

            if (todo is null)
                return new ApplicationError(ErrorTypes.TodoNotFound, $"Todo with id {id} not found");

            return todo.ToResponse();
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "An error occurred while retrieving the todo with id {Id}.", id);
            return new ApplicationError(
                ErrorTypes.DatabaseError,
                "An error occurred while retrieving the todo."
            );
        }
    }

    public async Task<OneOf<ApplicationError, List<TodoOccurrenceResponse>>> GetOccurrencesByTodoId(
        Guid todoId,
        int page = 1,
        int pageSize = 20
    )
    {
        try
        {
            var occurrences = await dbContext
                .TodoOccurrences.Include(o => o.Todo)
                .Where(o => o.TodoId == todoId)
                .OrderBy(o => o.OccurrenceDate)
                .Skip((page - 1) * pageSize)
                .Take(pageSize)
                .ToListAsync();

            return occurrences.ConvertAll(o => o.ToResponse());
        }
        catch (Exception ex)
        {
            logger.LogError(
                ex,
                "An error occurred while retrieving occurrences for todo with id {TodoId}.",
                todoId
            );
            return new ApplicationError(
                ErrorTypes.DatabaseError,
                "An error occurred while retrieving occurrences."
            );
        }
    }

    public async Task<OneOf<ApplicationError, List<TodoOccurrenceResponse>>> GetOccurrencesByDateRange(
        DateTimeOffset from,
        DateTimeOffset to
    )
    {
        if (from > to)
        {
            return new ApplicationError(
                ErrorTypes.InvalidInterval,
                "Start date must be earlier than end date."
            );
        }

        try
        {
            var occurrences = await dbContext
                .TodoOccurrences.Include(o => o.Todo)
                .Where(o => o.OccurrenceDate >= from && o.OccurrenceDate <= to)
                .OrderBy(o => o.OccurrenceDate)
                .ToListAsync();

            return occurrences.ConvertAll(o => o.ToResponse());
        }
        catch (Exception ex)
        {
            logger.LogError(
                ex,
                "An error occurred while retrieving occurrences between {From} and {To}.",
                from,
                to
            );
            return new ApplicationError(
                ErrorTypes.DatabaseError,
                "An error occurred while retrieving occurrences."
            );
        }
    }

    public async Task<OneOf<ApplicationError, List<TodoOccurrenceResponse>>> GetTodoHistory(
        Guid todoId,
        int page = 1,
        int pageSize = 20
    )
    {
        try
        {
            var occurrences = await dbContext
                .TodoOccurrences.Include(o => o.Todo)
                .Where(o => o.TodoId == todoId)
                .IgnoreQueryFilters([ApplicationDbContext.ACTIVE_OCCURRENCES_FILTER])
                .OrderByDescending(o => o.OccurrenceDate)
                .Skip((page - 1) * pageSize)
                .Take(pageSize)
                .ToListAsync();

            return occurrences.ConvertAll(o => o.ToResponse());
        }
        catch (Exception ex)
        {
            logger.LogError(
                ex,
                "An error occurred while retrieving occurrences for todo {TodoId}.",
                todoId
            );
            return new ApplicationError(
                ErrorTypes.DatabaseError,
                "An error occurred while retrieving occurrences."
            );
        }
    }
}
