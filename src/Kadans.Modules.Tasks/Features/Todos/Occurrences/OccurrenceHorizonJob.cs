using Kadans.Modules.Tasks.Persistence;
using Microsoft.EntityFrameworkCore;
using Quartz;
using TaskStatus = Kadans.Modules.Tasks.Domain.TaskStatus;

namespace Kadans.Modules.Tasks.Features.Todos.Occurrences;

/// <summary>
/// Keeps every active todo materialized up to the horizon. Scheduled by Quartz shortly after
/// startup and then every <see cref="TasksOptions.HorizonRefreshMinutes"/>; each run handles
/// todos in batches until none is behind the horizon.
/// </summary>
[DisallowConcurrentExecution]
internal sealed class OccurrenceHorizonJob(
    TasksDbContext dbContext,
    OccurrenceGenerator generator,
    ILogger<OccurrenceHorizonJob> logger
) : IJob
{
    public static readonly JobKey Key = new("occurrence-horizon", "tasks");

    private const int TodosPerBatch = 200;
    private const int MaxBatchesPerRun = 20;

    public async Task Execute(IJobExecutionContext context)
    {
        var cancellationToken = context.CancellationToken;
        var generated = 0;
        var todosSeen = 0;

        for (var batch = 0; batch < MaxBatchesPerRun; batch++)
        {
            var now = DateTimeOffset.UtcNow;
            var horizon = generator.HorizonFrom(now);

            // No user in a job scope: query filters would hide everything.
            var todos = await dbContext
                .Todos.IgnoreQueryFilters()
                .Include(t => t.RecurrenceRule)
                .Where(t =>
                    (t.Status == TaskStatus.Scheduled || t.Status == TaskStatus.Started)
                    && (t.OccurrencesGeneratedThrough == null || t.OccurrencesGeneratedThrough < horizon)
                )
                .OrderBy(t => t.OccurrencesGeneratedThrough)
                .Take(TodosPerBatch)
                .ToListAsync(cancellationToken);

            if (todos.Count == 0)
                break;

            foreach (var todo in todos)
                generated += await generator.EnsureGeneratedAsync(todo, now, cancellationToken);

            await dbContext.SaveChangesAsync(cancellationToken);
            dbContext.ChangeTracker.Clear();
            todosSeen += todos.Count;

            if (todos.Count < TodosPerBatch)
                break;
        }

        if (todosSeen > 0)
            logger.LogInformation("Occurrence horizon run: {Todos} todo(s), {Occurrences} occurrence(s) generated", todosSeen, generated);
    }
}
