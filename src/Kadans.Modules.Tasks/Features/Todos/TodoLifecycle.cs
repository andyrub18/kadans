using Kadans.Modules.Tasks.Domain;
using Kadans.Modules.Tasks.Persistence;
using Microsoft.EntityFrameworkCore;
using TaskStatus = Kadans.Modules.Tasks.Domain.TaskStatus;

namespace Kadans.Modules.Tasks.Features.Todos;

internal static class TodoLifecycle
{
    /// <summary>
    /// After an occurrence changed state: a bounded todo that is fully materialized and has nothing
    /// pending is finished. Indefinite todos never finish this way (they just wait for the horizon).
    /// </summary>
    public static async Task RefreshStatusAsync(TasksDbContext dbContext, Todo todo, CancellationToken cancellationToken = default)
    {
        if (!todo.IsActive || !todo.IsFullyGenerated)
            return;

        var statuses = dbContext.TodoOccurrences.IgnoreQueryFilters().Where(o => o.TodoId == todo.Id);
        if (await statuses.AnyAsync(o => o.Status == OccurrenceStatus.Pending, cancellationToken))
            return;

        var anyCompleted = await statuses.AnyAsync(o => o.Status == OccurrenceStatus.Completed, cancellationToken);
        todo.UpdateStatus(anyCompleted ? TaskStatus.Completed : TaskStatus.Cancelled);
    }
}
