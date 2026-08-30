using Kadans.Modules.Tasks.Domain;
using Kadans.Modules.Tasks.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace Kadans.Modules.Tasks.Features.Todos.Occurrences;

/// <summary>Materializes a todo's rule instances up to the rolling horizon. Callers save.</summary>
internal sealed class OccurrenceGenerator(TasksDbContext dbContext, IOptions<TasksOptions> options)
{
    public DateTimeOffset HorizonFrom(DateTimeOffset now) => now.AddDays(options.Value.OccurrenceHorizonDays);

    public async Task<int> EnsureGeneratedAsync(Todo todo, DateTimeOffset now, CancellationToken cancellationToken = default)
    {
        if (!todo.IsActive || todo.IsFullyGenerated)
            return 0;

        var horizon = HorizonFrom(now);
        if (todo.OccurrencesGeneratedThrough >= horizon)
            return 0;

        var from = todo.OccurrencesGeneratedThrough ?? todo.RecurrenceRule.StartDate;
        var existing = await dbContext
            .TodoOccurrences.IgnoreQueryFilters()
            .Where(o => o.TodoId == todo.Id && o.OriginalScheduledAt >= from)
            .Select(o => o.OriginalScheduledAt)
            .ToListAsync(cancellationToken);

        var plan = OccurrencePlanner.Next(
            todo.RecurrenceRule.Schedule,
            todo.OccurrencesGeneratedThrough,
            horizon,
            existing.ToHashSet(),
            options.Value.MaxOccurrencesPerBatch
        );

        dbContext.TodoOccurrences.AddRange(
            plan.ToInsert.Select(at => new TodoOccurrence
            {
                TodoId = todo.Id,
                OriginalScheduledAt = at,
                ScheduledAt = at,
            })
        );
        todo.OccurrencesGeneratedThrough = plan.GeneratedThrough;

        return plan.ToInsert.Count;
    }
}
