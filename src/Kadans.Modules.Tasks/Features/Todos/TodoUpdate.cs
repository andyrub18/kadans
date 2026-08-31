using TaskStatus = Kadans.Modules.Tasks.Domain.TaskStatus;
using Kadans.Modules.Tasks.Contracts;
using Kadans.Modules.Tasks.Domain;
using Kadans.Modules.Tasks.Features.Todos.Occurrences;
using Kadans.Modules.Tasks.Persistence;
using Kadans.SharedKernel.Errors;
using Microsoft.EntityFrameworkCore;
using OneOf;
using OneOf.Types;

namespace Kadans.Modules.Tasks.Features.Todos;

internal sealed class TodoUpdate(
    TasksDbContext dbContext,
    OccurrenceGenerator generator,
    ILogger<TodoUpdate> logger
)
{
    public async Task<OneOf<ApplicationError, TodoResponse>> UpdateTodo(Guid id, UpdateTodo update)
    {
        var todo = await dbContext
            .Todos.Include(t => t.RecurrenceRule)
            .Include(t => t.Remarks)
            .FirstOrDefaultAsync(t => t.Id == id);

        if (todo is null)
            return new ApplicationError(ErrorTypes.TodoNotFound, $"Todo with id {id} not found");

        if (update.PomodoroTemplateId is not null && !await dbContext.PomodoroTemplates.AnyAsync(t => t.Id == update.PomodoroTemplateId.Value))
            return new ApplicationError(ErrorTypes.PomodoroTemplateNotFound, $"Pomodoro template with id {update.PomodoroTemplateId} not found");

        var notificationChanged =
            todo.NotificationEnabled != update.NotificationEnabled
            || (update.NotifyBeforeInMinutes is not null && todo.NotificationLeadTime != TimeSpan.FromMinutes(update.NotifyBeforeInMinutes.Value));

        todo.Title = update.Title;
        todo.Description = update.Description;
        todo.NotificationEnabled = update.NotificationEnabled;
        todo.PomodoroTemplateId = update.PomodoroTemplateId;
        if (update.NotifyBeforeInMinutes is not null)
            todo.NotificationLeadTime = TimeSpan.FromMinutes(update.NotifyBeforeInMinutes.Value);
        todo.UpdatedAt = DateTimeOffset.UtcNow;

        RecurrenceRule? replacedRule = null;
        if (update.RecurrenceRule is not null)
        {
            var validation = await new CreateRecurrenceRulesValidator().ValidateAsync(update.RecurrenceRule);
            if (!validation.IsValid)
                return validation.ToValidationError("Validation failed for updating recurrence rule.");

            var newRule = update.RecurrenceRule.ToDomainRule();
            if (newRule.IsT0)
                return newRule.AsT0;

            replacedRule = await ReplaceRuleAsync(todo, newRule.AsT1);
        }

        if (notificationChanged)
        {
            var pending = await dbContext.TodoOccurrences.IgnoreQueryFilters().Where(o => o.TodoId == todo.Id && o.Status == OccurrenceStatus.Pending).ToListAsync();
            foreach (var occurrence in pending)
                occurrence.RefreshNotifyAt(todo);
        }

        try
        {
            await dbContext.SaveChangesAsync();

            // Only once the todo points at the new rule can the old one go (its FK cascades to todos).
            if (replacedRule is not null)
            {
                dbContext.RecurrenceRules.Remove(replacedRule);
                await dbContext.SaveChangesAsync();
            }

            logger.LogInformation("Updated todo {TodoId}", id);
            return todo.ToResponse();
        }
        catch (Exception e)
        {
            logger.LogError(e, "Error updating todo {TodoId}", id);
            return new ApplicationError(ErrorTypes.DatabaseError, $"Failed to update todo with id {id}.");
        }
    }

    /// <summary>
    /// Regenerates future instances for a new rule: untouched pending rows that the new rule does not
    /// produce are dropped, everything the user touched (rescheduled, remarked, completed, cancelled)
    /// is kept, and missing instances are materialized.
    /// </summary>
    private async Task<RecurrenceRule> ReplaceRuleAsync(Todo todo, RecurrenceRule newRule)
    {
        var now = DateTimeOffset.UtcNow;
        var oldRule = todo.RecurrenceRule;
        dbContext.RecurrenceRules.Add(newRule);

        var horizon = generator.HorizonFrom(now);
        var keep = newRule.GetOccurrences(newRule.StartDate, horizon).ToHashSet();

        var future = await dbContext
            .TodoOccurrences.IgnoreQueryFilters()
            .Where(o => o.TodoId == todo.Id && o.OriginalScheduledAt >= now)
            .ToListAsync();

        var stale = future.Where(o => o.IsUntouched && !keep.Contains(o.OriginalScheduledAt)).ToList();
        dbContext.TodoOccurrences.RemoveRange(stale);

        todo.ReplaceRule(newRule);

        var generated = await generator.EnsureGeneratedAsync(todo, now);
        logger.LogInformation("Rule replaced on todo {TodoId}: {Dropped} dropped, {Generated} generated", todo.Id, stale.Count, generated);
        return oldRule;
    }

    public async Task<OneOf<ApplicationError, TodoOccurrenceResponse>> RescheduleNextOccurrence(Guid todoId, RescheduleOccurrence request)
    {
        if (!await dbContext.Todos.AnyAsync(t => t.Id == todoId))
            return new ApplicationError(ErrorTypes.TodoNotFound, $"Todo with id {todoId} not found");

        var next = await dbContext
            .TodoOccurrences.Include(o => o.Todo)
            .Where(o => o.TodoId == todoId && o.ScheduledAt >= DateTimeOffset.UtcNow)
            .OrderBy(o => o.ScheduledAt)
            .FirstOrDefaultAsync();

        if (next is null)
            return new ApplicationError(ErrorTypes.NoFutureOccurrences, $"No pending occurrence to reschedule for todo {todoId}.");

        return await RescheduleAsync(next, request);
    }

    public async Task<OneOf<ApplicationError, TodoOccurrenceResponse>> RescheduleOccurrence(Guid occurrenceId, RescheduleOccurrence request)
    {
        var occurrence = await FindOccurrenceAsync(occurrenceId);
        if (occurrence is null)
            return new ApplicationError(ErrorTypes.TodoOccurrenceNotFound, $"Todo occurrence with id {occurrenceId} not found");

        return await RescheduleAsync(occurrence, request);
    }

    private async Task<OneOf<ApplicationError, TodoOccurrenceResponse>> RescheduleAsync(TodoOccurrence occurrence, RescheduleOccurrence request)
    {
        var result = occurrence.Reschedule(request.NewDate, request.Reason, DateTimeOffset.UtcNow);
        if (result.IsT0)
            return result.AsT0;

        await dbContext.SaveChangesAsync();
        logger.LogInformation("Rescheduled occurrence {OccurrenceId} to {NewDate}", occurrence.Id, request.NewDate);
        return occurrence.ToResponse();
    }

    public async Task<OneOf<ApplicationError, Success>> CompleteOccurrence(Guid occurrenceId)
    {
        var occurrence = await FindOccurrenceAsync(occurrenceId);
        if (occurrence is null)
            return new ApplicationError(ErrorTypes.TodoOccurrenceNotFound, $"Todo occurrence with id {occurrenceId} not found");

        var result = occurrence.Complete(DateTimeOffset.UtcNow);
        if (result.IsT0)
            return result.AsT0;

        // Persist first: the lifecycle check queries the database for pending rows.
        await dbContext.SaveChangesAsync();
        await TodoLifecycle.RefreshStatusAsync(dbContext, occurrence.Todo!);
        await dbContext.SaveChangesAsync();
        logger.LogInformation("Completed occurrence {OccurrenceId}", occurrenceId);
        return new Success();
    }

    public async Task<OneOf<ApplicationError, Success>> CancelOccurrence(Guid occurrenceId, string? reason)
    {
        var occurrence = await FindOccurrenceAsync(occurrenceId);
        if (occurrence is null)
            return new ApplicationError(ErrorTypes.TodoOccurrenceNotFound, $"Todo occurrence with id {occurrenceId} not found");

        var result = occurrence.Cancel(reason, DateTimeOffset.UtcNow);
        if (result.IsT0)
            return result.AsT0;

        await dbContext.SaveChangesAsync();
        await TodoLifecycle.RefreshStatusAsync(dbContext, occurrence.Todo!);
        await dbContext.SaveChangesAsync();
        logger.LogInformation("Cancelled occurrence {OccurrenceId}", occurrenceId);
        return new Success();
    }

    public async Task<OneOf<ApplicationError, Success>> CancelTodo(Guid id, string? reason)
    {
        var todo = await dbContext.Todos.FirstOrDefaultAsync(t => t.Id == id);
        if (todo is null)
            return new ApplicationError(ErrorTypes.TodoNotFound, $"Todo with id {id} not found");

        await using var transaction = await dbContext.Database.BeginTransactionAsync();
        try
        {
            var now = DateTimeOffset.UtcNow;
            await dbContext
                .TodoOccurrences.IgnoreQueryFilters()
                .Where(o => o.TodoId == id && o.Status == OccurrenceStatus.Pending)
                .ExecuteUpdateAsync(s =>
                    s.SetProperty(o => o.Status, OccurrenceStatus.Cancelled)
                        .SetProperty(o => o.CancelledAt, now)
                        .SetProperty(o => o.CancellationReason, reason)
                );

            todo.UpdateStatus(TaskStatus.Cancelled);
            await dbContext.SaveChangesAsync();
            await transaction.CommitAsync();

            logger.LogInformation("Cancelled todo {TodoId}", id);
            return new Success();
        }
        catch (Exception e)
        {
            await transaction.RollbackAsync();
            logger.LogError(e, "Error cancelling todo {TodoId}", id);
            return new ApplicationError(ErrorTypes.DatabaseError, $"Failed to cancel todo with id {id}.");
        }
    }

    public async Task<OneOf<ApplicationError, Success>> AddRemark(Guid id, string remark)
    {
        var todo = await dbContext.Todos.Include(t => t.Remarks).FirstOrDefaultAsync(t => t.Id == id);
        if (todo is null)
            return new ApplicationError(ErrorTypes.TodoNotFound, $"Todo with id {id} not found");

        todo.AddRemark(remark);
        await dbContext.SaveChangesAsync();
        return new Success();
    }

    public async Task<OneOf<ApplicationError, Success>> UpdateAllTodosRemarks(Guid todoId, IReadOnlyList<string> remarks)
    {
        var todo = await dbContext.Todos.Include(t => t.Remarks).FirstOrDefaultAsync(t => t.Id == todoId);
        if (todo is null)
            return new ApplicationError(ErrorTypes.TodoNotFound, $"Todo with id {todoId} not found");

        todo.Remarks = [.. remarks.Select(remark => new TodoRemark { Remark = remark })];
        todo.UpdatedAt = DateTimeOffset.UtcNow;
        await dbContext.SaveChangesAsync();
        return new Success();
    }

    public async Task<OneOf<ApplicationError, Success>> AddRemarkToOccurrence(Guid occurrenceId, string remark)
    {
        var occurrence = await FindOccurrenceAsync(occurrenceId);
        if (occurrence is null)
            return new ApplicationError(ErrorTypes.TodoOccurrenceNotFound, $"Todo occurrence with id {occurrenceId} not found");

        occurrence.Remarks = string.IsNullOrWhiteSpace(remark) ? null : remark;
        await dbContext.SaveChangesAsync();
        return new Success();
    }

    /// <summary>Any status (so "already completed" is reported precisely), but only the current user's.</summary>
    private Task<TodoOccurrence?> FindOccurrenceAsync(Guid occurrenceId) =>
        dbContext
            .TodoOccurrences.IgnoreQueryFilters([TasksDbContext.ACTIVE_OCCURRENCES_FILTER])
            .Include(o => o.Todo)
            .FirstOrDefaultAsync(o => o.Id == occurrenceId);
}
