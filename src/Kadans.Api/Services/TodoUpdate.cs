using Kadans.Api.BackgroundTasks;
using Kadans.Api.Data;
using Kadans.Api.DTOs;
using Kadans.SharedKernel.Errors;
using Kadans.Api.Models;
using Microsoft.EntityFrameworkCore;
using OneOf;

namespace Kadans.Api.Services;

public sealed class TodoUpdate(
    ApplicationDbContext context,
    IBackgroundTaskQueue queue,
    ILogger<TodoUpdate> logger
)
{
    public async Task<OneOf<ApplicationError, bool>> UpdateTodo(Guid id, UpdateTodo update)
    {
        var todo = await context
            .Todos.Include(t => t.RecurrenceRule)
            .Include(t => t.Remarks)
            .FirstOrDefaultAsync(t => t.Id == id);

        if (todo is null)
            return new ApplicationError(ErrorTypes.TodoNotFound, $"Todo with id {id} not found");

        todo.Title = update.Title;
        todo.Description = update.Description;
        todo.NotificationEnabled = update.NotificationEnabled;
        todo.UpdateStatus(update.Status);

        if (update.PomodoroTemplateId is not null)
        {
            var templateExists = await context.PomodoroTemplates.AnyAsync(t =>
                t.Id == update.PomodoroTemplateId.Value
            );

            if (!templateExists)
            {
                return new ApplicationError(
                    ErrorTypes.PomodoroTemplateNotFound,
                    $"Pomodoro template with id {update.PomodoroTemplateId} not found"
                );
            }
        }

        todo.PomodoroTemplateId = update.PomodoroTemplateId;

        var newRule = RecurrenceRule.Create(
            update.RecurrenceRule.Frequency,
            update.RecurrenceRule.StartDate,
            update.RecurrenceRule.Interval,
            update.RecurrenceRule.ByHour,
            update.RecurrenceRule.ByMinute,
            update.RecurrenceRule.ByDayOfWeek,
            update.RecurrenceRule.ByMonthDay,
            update.RecurrenceRule.BySetPos,
            update.RecurrenceRule.ByMonth,
            update.RecurrenceRule.Count,
            update.RecurrenceRule.Until,
            update.RecurrenceRule.Exceptions,
            update.RecurrenceRule.TimeZone
        );

        if (newRule.IsT0)
            return newRule.AsT0;
        todo.RecurrenceRule = newRule.AsT1;

        try
        {
            await context.SaveChangesAsync();

            queue.EnqueueBackgroundWorkItem(
                async (sp, ct) =>
                {
                    var scopeDbContext = sp.GetRequiredService<ApplicationDbContext>();
                    // Cancel future occurrences of the old rule
                    await scopeDbContext
                        .TodoOccurrences.Where(o =>
                            o.TodoId == id
                            && o.OccurrenceDate >= DateTimeOffset.UtcNow
                            && !o.IsCompleted
                            && !o.IsCancelled
                        )
                        .ExecuteUpdateAsync(
                            s =>
                                s.SetProperty(p => p.IsCancelled, true)
                                    .SetProperty(
                                        p => p.CancellationReason,
                                        "Todo updated, cancelling future occurrences"
                                    ),
                            ct
                        );
                    // Generate new occurrences based on the updated rule
                    DateTimeOffset endDate;
                    var startDate =
                        update.RecurrenceRule.StartDate > DateTimeOffset.UtcNow
                            ? update.RecurrenceRule.StartDate
                            : DateTimeOffset.UtcNow;
                    if (update.RecurrenceRule.Until is not null)
                    {
                        endDate = update.RecurrenceRule.Until.Value;
                    }
                    else if (update.RecurrenceRule.Count is not null)
                    {
                        endDate = newRule.AsT1.GetEffectiveEndDate()!.Value;
                    }
                    else
                    {
                        // Default to one year from start date if no end specified
                        endDate = startDate.AddYears(1);
                    }

                    var occurrences = todo
                        .RecurrenceRule.GetOccurrences(startDate, endDate)
                        .Select(date => new TodoOccurrence
                        {
                            TodoId = todo.Id,
                            OccurrenceDate = date,
                            Todo = todo,
                        })
                        .ToList();

                    logger.LogInformation(
                        "Generating {Count} occurrences for updated todo with id {Id}",
                        occurrences.Count,
                        id
                    );

                    try
                    {
                        await scopeDbContext.TodoOccurrences.AddRangeAsync(occurrences, ct);
                        await scopeDbContext.SaveChangesAsync(ct);
                    }
                    catch (Exception e)
                    {
                        logger.LogError(
                            e,
                            "Error generating occurrences for updated todo with id {Id}: {Message}",
                            id,
                            e.Message
                        );
                    }
                }
            );

            logger.LogInformation("Updated todo with id {Id}", id);
            return true;
        }
        catch (Exception e)
        {
            logger.LogError(e, "Error updating todo with id {Id}: {Message}", id, e.Message);
            return new ApplicationError(
                ErrorTypes.DatabaseError,
                $"Failed to update todo with id {id}."
            );
        }
    }

    public async Task<OneOf<ApplicationError, bool>> RescheduleNextOccurrence(
        Guid id,
        RescheduleNextOccurrence request
    )
    {
        if (request.NewDate <= DateTimeOffset.UtcNow)
        {
            return new ApplicationError(ErrorTypes.InvalidDueDate, "Invalid due date");
        }

        var todo = await context
            .Todos.Include(t => t.RecurrenceRule)
            .FirstOrDefaultAsync(t => t.Id == id);

        if (todo is null)
            return new ApplicationError(ErrorTypes.TodoNotFound, $"Todo with id {id} not found");

        if (todo.RecurrenceRule.IsOneTime)
        {
            var occurrence = await context
                .TodoOccurrences.Where(o => o.TodoId == id)
                .FirstOrDefaultAsync();

            if (occurrence!.IsCompleted)
            {
                return new ApplicationError(
                    ErrorTypes.TaskAlreadyCompleted,
                    $"The task with id {id} already completed"
                );
            }

            if (occurrence.IsCancelled)
            {
                return new ApplicationError(
                    ErrorTypes.TaskAlreadyCancelled,
                    $"The task with id {id} already cancelled"
                );
            }

            var recurrenceRule = RecurrenceRule.CreateOneTimeRule(request.NewDate);
            if (recurrenceRule.IsT0)
                return recurrenceRule.AsT0;
            todo.RecurrenceRule = recurrenceRule.AsT1;

            try
            {
                await context.SaveChangesAsync();
                logger.LogInformation(
                    "Rescheduled one-time todo with id {Id} to new date {Date}",
                    id,
                    request.NewDate
                );
                queue.EnqueueBackgroundWorkItem(
                    async (sp, ct) =>
                    {
                        var scopeDbContext = sp.GetRequiredService<ApplicationDbContext>();
                        // Cancel the existing occurrence
                        await scopeDbContext
                            .TodoOccurrences.Where(o =>
                                o.TodoId == id && !o.IsCompleted && !o.IsCancelled
                            )
                            .ExecuteUpdateAsync(
                                s =>
                                    s.SetProperty(p => p.IsCancelled, true)
                                        .SetProperty(p => p.CancellationReason, request.Reason),
                                ct
                            );
                        // Create a new occurrence for the new date
                        var newOccurrence = new TodoOccurrence
                        {
                            TodoId = id,
                            OccurrenceDate = request.NewDate,
                            Todo = todo,
                        };
                        await scopeDbContext.TodoOccurrences.AddAsync(newOccurrence, ct);
                        await scopeDbContext.SaveChangesAsync(ct);
                    }
                );

                return true;
            }
            catch (Exception e)
            {
                logger.LogError(
                    e,
                    "Error rescheduling one-time todo with id {Id}: {Message}",
                    id,
                    e.Message
                );
                return new ApplicationError(
                    ErrorTypes.DatabaseError,
                    $"Failed to reschedule todo with id {id}."
                );
            }
        }

        var nextOccurrenceDate = todo.RecurrenceRule.GetNextOccurrence();

        if (nextOccurrenceDate is null)
        {
            return new ApplicationError(
                ErrorTypes.NoFutureOccurrences,
                $"No future occurrences to reschedule for todo with id {id}."
            );
        }

        var result = todo.RescheduleNextOccurrence(request.NewDate);
        if (result.IsT0)
            return result.AsT0;

        try
        {
            await context.Todos.AddAsync(result.AsT1);
            await context.SaveChangesAsync();
            logger.LogInformation(
                "Rescheduled next occurrence of todo with id {Id} to new date {Date}",
                id,
                request.NewDate
            );
            queue.EnqueueBackgroundWorkItem(
                async (sp, ct) =>
                {
                    var scopeDbContext = sp.GetRequiredService<ApplicationDbContext>();
                    var rescheduledOccurrence = await scopeDbContext
                        .TodoOccurrences.Where(o =>
                            o.TodoId == result.AsT1.Id
                            && o.OccurrenceDate == nextOccurrenceDate.Value
                            && !o.IsCompleted
                            && !o.IsCancelled
                        )
                        .FirstOrDefaultAsync(ct);
                    if (rescheduledOccurrence is not null)
                    {
                        rescheduledOccurrence.IsCancelled = true;
                        rescheduledOccurrence.CancellationReason =
                            $"Rescheduled to {request.NewDate}";
                        await scopeDbContext.SaveChangesAsync(ct);
                    }
                    var occurrence = new TodoOccurrence
                    {
                        TodoId = result.AsT1.Id,
                        OccurrenceDate = request.NewDate,
                        Todo = result.AsT1,
                    };

                    try
                    {
                        await scopeDbContext.TodoOccurrences.AddAsync(occurrence, ct);
                        await scopeDbContext.SaveChangesAsync(ct);
                    }
                    catch (Exception e)
                    {
                        logger.LogError(
                            e,
                            "Error creating todo occurrence for one-time todo: {Message}",
                            e.Message
                        );
                    }
                }
            );
            return true;
        }
        catch (Exception e)
        {
            logger.LogError(
                e,
                "Error rescheduling next occurrence of todo with id {Id}: {Message}",
                id,
                e.Message
            );
            return new ApplicationError(
                ErrorTypes.DatabaseError,
                $"Failed to reschedule next occurrence of todo with id {id}."
            );
        }
    }

    // Cancelling a todo occurrence
    public async Task<OneOf<ApplicationError, bool>> CancelOccurrence(
        Guid occurrenceId,
        string reason
    )
    {
        var occurrence = await context
            .TodoOccurrences.Include(o => o.Todo)
            .FirstOrDefaultAsync(o => o.Id == occurrenceId);

        if (occurrence is null)
        {
            return new ApplicationError(
                ErrorTypes.TodoOccurrenceNotFound,
                $"Todo occurrence with id {occurrenceId} not found"
            );
        }

        if (occurrence.IsCompleted)
        {
            return new ApplicationError(
                ErrorTypes.TaskAlreadyCompleted,
                $"The task occurrence with id {occurrenceId} already completed"
            );
        }

        if (occurrence.IsCancelled)
        {
            return new ApplicationError(
                ErrorTypes.TaskAlreadyCancelled,
                $"The task occurrence with id {occurrenceId} already cancelled"
            );
        }

        occurrence.IsCancelled = true;
        occurrence.CancellationReason = reason;

        try
        {
            await context.SaveChangesAsync();
            logger.LogInformation(
                "Cancelled todo occurrence with id {Id} for reason: {Reason}",
                occurrenceId,
                reason
            );
            // If all the occurrences of a todo are cancelled, mark the todo as cancelled
            var hasPendingOccurrences = await context.TodoOccurrences.AnyAsync(o =>
                o.TodoId == occurrence.TodoId && !o.IsCompleted && !o.IsCancelled
            );
            if (!hasPendingOccurrences)
            {
                var todo = await context.Todos.FirstOrDefaultAsync(t => t.Id == occurrence.TodoId);
                if (todo is not null)
                {
                    todo.UpdateStatus(Models.TaskStatus.Cancelled);
                    await context.SaveChangesAsync();
                    logger.LogInformation("Marked todo with id {Id} as cancelled", todo.Id);
                }
            }
            return true;
        }
        catch (Exception e)
        {
            logger.LogError(
                e,
                "Error cancelling todo occurrence with id {Id}: {Message}",
                occurrenceId,
                e.Message
            );
            return new ApplicationError(
                ErrorTypes.DatabaseError,
                $"Failed to cancel todo occurrence with id {occurrenceId}."
            );
        }
    }

    // Add Remark to a todo
    public async Task<OneOf<ApplicationError, bool>> AddRemark(Guid id, string remark)
    {
        var todo = await context.Todos.Include(t => t.Remarks).FirstOrDefaultAsync(t => t.Id == id);
        if (todo is null)
        {
            return new ApplicationError(ErrorTypes.TodoNotFound, $"Todo with id {id} not found");
        }

        var todoRemark = new TodoRemark { Remark = remark, CreatedAt = DateTimeOffset.UtcNow };

        todo.Remarks.Add(todoRemark);

        try
        {
            await context.SaveChangesAsync();
            logger.LogInformation("Added remark to todo with id {Id}", id);
            return true;
        }
        catch (Exception e)
        {
            logger.LogError(
                e,
                "Error adding remark to todo with id {Id}: {Message}",
                id,
                e.Message
            );
            return new ApplicationError(
                ErrorTypes.DatabaseError,
                $"Failed to add remark to todo with id {id}."
            );
        }
    }

    public async Task<OneOf<ApplicationError, bool>> CancelTodo(Guid id, string reason)
    {
        var todo = await context.Todos.FirstOrDefaultAsync(t => t.Id == id);
        if (todo is null)
        {
            return new ApplicationError(ErrorTypes.TodoNotFound, $"Todo with id {id} not found");
        }
        await using var transaction = await context.Database.BeginTransactionAsync();

        try
        {
            // Cancel all future occurrences of this todo
            await context
                .TodoOccurrences.Where(o =>
                    o.TodoId == id
                    && o.OccurrenceDate >= DateTimeOffset.UtcNow
                    && !o.IsCompleted
                    && !o.IsCancelled
                )
                .ExecuteUpdateAsync(s =>
                    s.SetProperty(p => p.IsCancelled, true)
                        .SetProperty(p => p.CancellationReason, reason)
                );
            todo.UpdateStatus(Models.TaskStatus.Cancelled);
            await context.SaveChangesAsync();
            await transaction.CommitAsync();

            logger.LogInformation("Cancelled todo with id {Id} for reason: {Reason}", id, reason);
            return true;
        }
        catch (Exception e)
        {
            await transaction.RollbackAsync();
            logger.LogError(e, "Error cancelling todo with id {Id}: {Message}", id, e.Message);
            return new ApplicationError(
                ErrorTypes.DatabaseError,
                $"Failed to cancel todo with id {id}."
            );
        }
    }

    public async Task<OneOf<ApplicationError, bool>> CompleteOccurrence(Guid occurrenceId)
    {
        var occurrence = await context
            .TodoOccurrences.Include(o => o.Todo)
            .FirstOrDefaultAsync(o => o.Id == occurrenceId);

        if (occurrence is null)
        {
            return new ApplicationError(
                ErrorTypes.TodoOccurrenceNotFound,
                $"Todo occurrence with id {occurrenceId} not found"
            );
        }

        if (occurrence.IsCompleted)
        {
            return new ApplicationError(
                ErrorTypes.TaskAlreadyCompleted,
                $"The task occurrence with id {occurrenceId} already completed"
            );
        }

        if (occurrence.IsCancelled)
        {
            return new ApplicationError(
                ErrorTypes.TaskAlreadyCancelled,
                $"The task occurrence with id {occurrenceId} already cancelled"
            );
        }

        occurrence.IsCompleted = true;
        occurrence.OccurrenceDate = DateTimeOffset.UtcNow; // Update occurrence date to completion time

        try
        {
            await context.SaveChangesAsync();
            logger.LogInformation("Completed todo occurrence with id {Id}", occurrenceId);
            // If all the occurrences of a todo are completed, mark the todo as completed
            var hasPendingOccurrences = await context.TodoOccurrences.AnyAsync(o =>
                o.TodoId == occurrence.TodoId && !o.IsCompleted && !o.IsCancelled
            );
            if (!hasPendingOccurrences)
            {
                var todo = await context.Todos.FirstOrDefaultAsync(t => t.Id == occurrence.TodoId);
                if (todo is not null)
                {
                    todo.UpdateStatus(Models.TaskStatus.Completed);
                    await context.SaveChangesAsync();
                    logger.LogInformation("Marked todo with id {Id} as completed", todo.Id);
                }
            }
            return true;
        }
        catch (Exception e)
        {
            logger.LogError(
                e,
                "Error completing todo occurrence with id {Id}: {Message}",
                occurrenceId,
                e.Message
            );
            return new ApplicationError(
                ErrorTypes.DatabaseError,
                $"Failed to complete todo occurrence with id {occurrenceId}."
            );
        }
    }

    public async Task<OneOf<ApplicationError, bool>> AddRemarkToOccurrence(
        Guid occurrenceId,
        string remark
    )
    {
        var occurrence = await context
            .TodoOccurrences.Include(o => o.Todo)
            .FirstOrDefaultAsync(o => o.Id == occurrenceId);

        if (occurrence is null)
        {
            return new ApplicationError(
                ErrorTypes.TodoOccurrenceNotFound,
                $"Todo occurrence with id {occurrenceId} not found"
            );
        }

        occurrence.Remarks = remark;

        try
        {
            await context.SaveChangesAsync();
            logger.LogInformation("Added remark to todo occurrence with id {Id}", occurrenceId);
            return true;
        }
        catch (Exception e)
        {
            logger.LogError(
                e,
                "Error adding remark to todo occurrence with id {Id}: {Message}",
                occurrenceId,
                e.Message
            );
            return new ApplicationError(
                ErrorTypes.DatabaseError,
                $"Failed to add remark to todo occurrence with id {occurrenceId}."
            );
        }
    }

    public async Task<OneOf<ApplicationError, bool>> UpdateAllTodosRemarks(
        Guid todoId,
        List<TodoRemark> remarks
    )
    {
        var todo = await context
            .Todos.Include(t => t.Remarks)
            .FirstOrDefaultAsync(t => t.Id == todoId);

        if (todo is null)
        {
            return new ApplicationError(
                ErrorTypes.TodoNotFound,
                $"Todo with id {todoId} not found"
            );
        }

        todo.Remarks = remarks;

        try
        {
            await context.SaveChangesAsync();
            logger.LogInformation("Updated remarks for todo with id {Id}", todoId);
            return true;
        }
        catch (Exception e)
        {
            logger.LogError(
                e,
                "Error updating remarks for todo with id {Id}: {Message}",
                todoId,
                e.Message
            );
            return new ApplicationError(
                ErrorTypes.DatabaseError,
                $"Failed to update remarks for todo with id {todoId}."
            );
        }
    }
}
