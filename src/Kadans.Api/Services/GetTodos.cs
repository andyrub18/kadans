using Kadans.Api.Data;
using Kadans.SharedKernel.Errors;
using Kadans.Api.Models;
using Microsoft.EntityFrameworkCore;
using OneOf;
using TaskStatus = Kadans.Api.Models.TaskStatus;

namespace Kadans.Api.Services;

public sealed class GetTodos(ApplicationDbContext dbContext, ILogger<GetTodos> logger)
{
    public async Task<OneOf<ApplicationError, List<Todo>>> GetAllTodos(
        int page,
        int pageSize,
        TaskStatus? status
    )
    {
        try
        {
            var todos = await dbContext
                .Todos.IgnoreQueryFilters([ApplicationDbContext.ACTIVE_TODOS_FILTER])
                .Where(t => status == null || t.Status == status)
                .OrderByDescending(t => t.CreatedAt)
                .Skip((page - 1) * pageSize)
                .Take(pageSize)
                .ToListAsync();

            return todos;
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

    public async Task<OneOf<ApplicationError, Todo>> GetTodoById(Guid id)
    {
        try
        {
            var todo = await dbContext
                .Todos.Include(t => t.RecurrenceRule)
                .Include(t => t.Remarks)
                .Include(t => t.PomodoroTemplate)
                    .ThenInclude(t => t!.Phases)
                .FirstOrDefaultAsync(t => t.Id == id);

            return todo
                ?? (OneOf<ApplicationError, Todo>)
                    new ApplicationError(ErrorTypes.TodoNotFound, $"Todo with id {id} not found");
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

    public async Task<OneOf<ApplicationError, List<TodoOccurrence>>> GetOccurrencesByTodoId(
        Guid todoId,
        int page = 1,
        int pageSize = 20
    )
    {
        try
        {
            var occurrences = await dbContext
                .TodoOccurrences.Where(o => o.TodoId == todoId)
                .OrderBy(o => o.OccurrenceDate)
                .Skip((page - 1) * pageSize)
                .Take(pageSize)
                .ToListAsync();

            return occurrences;
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

    public async Task<OneOf<ApplicationError, List<TodoOccurrence>>> GetOccurrencesByDateRange(
        DateTimeOffset startDate,
        DateTimeOffset endDate
    )
    {
        if (startDate > endDate)
        {
            return new ApplicationError(
                ErrorTypes.InvalidInterval,
                "Start date must be earlier than end date."
            );
        }
        try
        {
            var occurrences = await dbContext
                .TodoOccurrences.Where(o =>
                    o.OccurrenceDate >= startDate && o.OccurrenceDate <= endDate
                )
                .Include(o => o.Todo)
                .OrderBy(o => o.OccurrenceDate)
                .ToListAsync();

            return occurrences;
        }
        catch (Exception ex)
        {
            logger.LogError(
                ex,
                "An error occurred while retrieving occurrences between {StartDate} and {EndDate}.",
                startDate,
                endDate
            );
            return new ApplicationError(
                ErrorTypes.DatabaseError,
                "An error occurred while retrieving occurrences."
            );
        }
    }

    public async Task<OneOf<ApplicationError, List<TodoOccurrence>>> GetTodoHistory(
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

            return occurrences;
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
