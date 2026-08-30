using Kadans.Modules.Tasks.Domain;
using Kadans.Modules.Tasks.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using TaskStatus = Kadans.Modules.Tasks.Domain.TaskStatus;

namespace Kadans.Modules.Tasks.Features.Todos.Occurrences;

/// <summary>
/// Keeps every active todo materialized up to the horizon. Runs shortly after startup and then
/// periodically; each pass handles todos in batches until none is behind the horizon.
/// </summary>
internal sealed class OccurrenceHorizonJob(
    IServiceScopeFactory scopeFactory,
    IOptions<TasksOptions> options,
    ILogger<OccurrenceHorizonJob> logger
) : BackgroundService
{
    private const int TodosPerBatch = 200;
    private const int MaxBatchesPerPass = 20;

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        try
        {
            await Task.Delay(TimeSpan.FromSeconds(10), stoppingToken);
        }
        catch (OperationCanceledException)
        {
            return;
        }

        using var timer = new PeriodicTimer(TimeSpan.FromMinutes(Math.Max(1, options.Value.HorizonRefreshMinutes)));
        do
        {
            try
            {
                await RunOnceAsync(stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                return;
            }
            catch (Exception ex)
            {
                logger.LogError(ex, "Occurrence horizon pass failed");
            }
        } while (await timer.WaitForNextTickAsync(stoppingToken));
    }

    public async Task RunOnceAsync(CancellationToken cancellationToken)
    {
        var generated = 0;
        var todosSeen = 0;

        for (var batch = 0; batch < MaxBatchesPerPass; batch++)
        {
            using var scope = scopeFactory.CreateScope();
            var dbContext = scope.ServiceProvider.GetRequiredService<TasksDbContext>();
            var generator = scope.ServiceProvider.GetRequiredService<OccurrenceGenerator>();

            var now = DateTimeOffset.UtcNow;
            var horizon = generator.HorizonFrom(now);

            // No user in this scope: query filters would hide everything.
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
            todosSeen += todos.Count;

            if (todos.Count < TodosPerBatch)
                break;
        }

        if (todosSeen > 0)
            logger.LogInformation("Occurrence horizon pass: {Todos} todo(s), {Occurrences} occurrence(s) generated", todosSeen, generated);
    }
}
