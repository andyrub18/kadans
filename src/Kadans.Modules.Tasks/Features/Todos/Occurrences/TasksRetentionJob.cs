using System.Linq.Expressions;
using Kadans.Modules.Tasks.Domain;
using Kadans.Modules.Tasks.Persistence;
using Kadans.SharedKernel.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Quartz;

namespace Kadans.Modules.Tasks.Features.Todos.Occurrences;

/// <summary>
/// Nightly: occurrences nobody acted on (still pending, never moved, no remark) go once they are
/// <see cref="TasksOptions.UntouchedOccurrenceRetentionDays"/> old. A reminder every 5 minutes leaves 288 a day. What
/// someone completed, cancelled, moved or annotated stays as their history; todos and Pomodoro history stay until
/// the person deletes them.
/// </summary>
[DisallowConcurrentExecution]
internal sealed class TasksRetentionJob(TasksDbContext dbContext, IOptions<TasksOptions> options, ILogger<TasksRetentionJob> logger) : IJob
{
    public static readonly JobKey Key = new("tasks-retention", "tasks");

    public Task Execute(IJobExecutionContext context) => RunAsync(DateTimeOffset.UtcNow, context.CancellationToken);

    internal async Task RunAsync(DateTimeOffset now, CancellationToken cancellationToken)
    {
        var days = options.Value.UntouchedOccurrenceRetentionDays;
        var removed = await Retention.DeleteInBatchesAsync(
            dbContext.TodoOccurrences.IgnoreQueryFilters().Where(Disposable(now.AddDays(-days))),
            o => o.Id,
            cancellationToken
        );
        logger.LogInformation("Retention: removed {Count} untouched occurrence(s) older than {Days} days", removed, days);
    }

    /// <summary>Nobody acted on it, and it was due before <paramref name="cutoff"/>.</summary>
    internal static Expression<Func<TodoOccurrence, bool>> Disposable(DateTimeOffset cutoff) =>
        o => o.Status == OccurrenceStatus.Pending && o.RescheduledAt == null && o.Remarks == null && o.ScheduledAt < cutoff;
}
