using Kadans.Modules.Tasks.Domain;
using Kadans.Modules.Tasks.Persistence;
using Kadans.SharedKernel.Errors;
using Kadans.SharedKernel.Persistence;
using Kadans.SharedKernel.Security;
using Kadans.SharedKernel.Users;
using Microsoft.EntityFrameworkCore;
using OneOf;
using OneOf.Types;

namespace Kadans.Modules.Tasks.Features.Todos;

/// <summary>
/// Deleting for good, unlike cancelling: one todo the person deletes (its occurrences, remarks and focus history go
/// with it, and its stats with them), or everything of an account being erased. Occurrences go first, in batches: a
/// reminder every 5 minutes leaves tens of thousands. A rule deleted takes its todo and the todo's remarks along.
/// </summary>
internal sealed class TodoErasure(TasksDbContext dbContext, ICurrentUserService currentUser, ILogger<TodoErasure> logger) : IUserDataEraser
{
    public async Task<OneOf<ApplicationError, Success>> DeleteTodo(Guid id, CancellationToken cancellationToken = default)
    {
        // The usual filters: the current user's todo, finished or not.
        var exists = await dbContext.Todos.IgnoreQueryFilters([TasksDbContext.ACTIVE_TODOS_FILTER]).AnyAsync(t => t.Id == id, cancellationToken);
        if (!exists)
            return new ApplicationError(ErrorTypes.TodoNotFound, $"Todo with id {id} not found");

        await EraseTodosAsync(dbContext.Todos.IgnoreQueryFilters().Where(t => t.Id == id && t.UserId == currentUser.UserId), cancellationToken);
        logger.LogInformation("Deleted todo {TodoId}", id);
        return new Success();
    }

    public async Task EraseAsync(string userId, CancellationToken cancellationToken = default)
    {
        await EraseTodosAsync(dbContext.Todos.IgnoreQueryFilters().Where(t => t.UserId == userId), cancellationToken);
        await dbContext.PomodoroRuns.IgnoreQueryFilters().Where(r => r.UserId == userId).ExecuteDeleteAsync(cancellationToken);
        await dbContext.PomodoroTemplates.IgnoreQueryFilters().Where(t => t.UserId == userId).ExecuteDeleteAsync(cancellationToken);
    }

    private async Task EraseTodosAsync(IQueryable<Todo> todos, CancellationToken cancellationToken)
    {
        var todoIds = todos.Select(t => t.Id);
        await Retention.DeleteInBatchesAsync(
            dbContext.TodoOccurrences.IgnoreQueryFilters().Where(o => todoIds.Contains(o.TodoId)),
            o => o.Id,
            cancellationToken
        );
        // Runs and their phases (the stats come from them).
        await dbContext.PomodoroRuns.IgnoreQueryFilters().Where(r => todoIds.Contains(r.TodoId)).ExecuteDeleteAsync(cancellationToken);
        var ruleIds = await todos.Select(t => t.RecurrenceRule.Id).ToListAsync(cancellationToken);
        await todos.ExecuteDeleteAsync(cancellationToken);
        await dbContext.RecurrenceRules.Where(r => ruleIds.Contains(r.Id)).ExecuteDeleteAsync(cancellationToken);
    }
}
