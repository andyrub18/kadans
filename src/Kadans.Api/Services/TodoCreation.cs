using Kadans.SharedKernel.Security;
using Kadans.Api.BackgroundTasks;
using Kadans.Api.Data;
using Kadans.Api.DTOs;
using Kadans.SharedKernel.Errors;
using Kadans.Api.Models;
using Kadans.Api.Security;
using Kadans.Api.Validators;
using Microsoft.EntityFrameworkCore;
using OneOf;

namespace Kadans.Api.Services;

public sealed class TodoCreation(
    ApplicationDbContext dbContext,
    ILogger<TodoCreation> logger,
    IBackgroundTaskQueue queue,
    ICurrentUserService currentUserService
)
{
    public async Task<OneOf<ApplicationError, bool>> CreateOneTimeTodo(CreateOneTimeTodo request)
    {
        // validate the request
        var validationResult = await new CreateOneTimeTodoRulesValidator().ValidateAsync(request);
        if (!validationResult.IsValid)
        {
            logger.LogWarning(
                "Validation failed for CreateOneTimeTodo: {@errors}",
                validationResult.Errors
            );
            return new ValidationError(
                ErrorTypes.ValidationError,
                "Validation failed for creating one-time todo.",
                validationResult.Errors.ConvertAll(e => (e.ErrorCode!, e.ErrorMessage))
            );
        }

        var userId = currentUserService.UserId;
        if (userId is null)
        {
            logger.LogWarning("Unauthenticated user attempted to create a todo.");
            return new ApplicationError(
                ErrorTypes.Unauthorized,
                "User must be authenticated to create a todo."
            );
        }

        var rule = RecurrenceRule.CreateOneTimeRule(request.DueDate);

        if (rule.IsT0)
            return rule.AsT0;

        if (request.PomodoroTemplateId is not null)
        {
            var templateExists = await dbContext.PomodoroTemplates.AnyAsync(t =>
                t.Id == request.PomodoroTemplateId.Value
            );

            if (!templateExists)
            {
                return new ApplicationError(
                    ErrorTypes.PomodoroTemplateNotFound,
                    $"Pomodoro template with id {request.PomodoroTemplateId} not found."
                );
            }
        }

        var todo = new Todo(
            title: request.Title,
            description: request.Description,
            recurrenceRule: rule.AsT1,
            notificationEnabled: request.NotificationEnabled
        )
        {
            PomodoroTemplateId = request.PomodoroTemplateId,
            UserId = userId,
            NotificationLeadTime = TimeSpan.FromMinutes(request.NotifyBeforeInMinutes),
        };

        try
        {
            await dbContext.Todos.AddAsync(todo);
            var saved = await dbContext.SaveChangesAsync();
            if (saved > 0)
            {
                // If the task is created successfully, enqueue a background task to create its occurrences
                queue.EnqueueBackgroundWorkItem(
                    async (sp, ct) =>
                    {
                        var scopeDbContext = sp.GetRequiredService<ApplicationDbContext>();
                        var occurrence = new TodoOccurrence
                        {
                            TodoId = todo.Id,
                            OccurrenceDate = request.DueDate,
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
            }
            return true;
        }
        catch (Exception e)
        {
            logger.LogError(e, "Error creating one-time todo: {Message}", e.Message);
            return new ApplicationError(
                ErrorTypes.DatabaseError,
                "Failed to create one-time todo."
            );
        }
    }

    public async Task<OneOf<ApplicationError, bool>> CreateRecurringTodo(
        CreateRecurringTodo request
    )
    {
        var validationResult = await new CreateRecurringTodoRulesValidator().ValidateAsync(request);
        if (!validationResult.IsValid)
        {
            logger.LogWarning(
                "Validation failed for CreateRecurringTodo: {@errors}",
                validationResult.Errors
            );
            return new ValidationError(
                ErrorTypes.ValidationError,
                "Validation failed for creating recurring todo.",
                validationResult.Errors.ConvertAll(e => (e.ErrorCode!, e.ErrorMessage))
            );
        }

        var userId = currentUserService.UserId;
        if (userId is null)
        {
            logger.LogWarning("Unauthenticated user attempted to create a todo.");
            return new ApplicationError(
                ErrorTypes.Unauthorized,
                "User must be authenticated to create a todo."
            );
        }

        var rule = RecurrenceRule.Create(
            frequency: request.RecurrenceRule.Frequency,
            startDate: request.RecurrenceRule.StartDate,
            interval: request.RecurrenceRule.Interval,
            byHour: request.RecurrenceRule.ByHour,
            byMinute: request.RecurrenceRule.ByMinute,
            byDayOfWeek: request.RecurrenceRule.ByDayOfWeek,
            byMonthDay: request.RecurrenceRule.ByMonthDay,
            bySetPos: request.RecurrenceRule.BySetPos,
            byMonth: request.RecurrenceRule.ByMonth,
            count: request.RecurrenceRule.Count,
            until: request.RecurrenceRule.Until,
            exceptions: request.RecurrenceRule.Exceptions,
            timeZoneId: request.RecurrenceRule.TimeZone
        );

        if (rule.IsT0)
            return rule.AsT0;

        if (request.PomodoroTemplateId is not null)
        {
            var templateExists = await dbContext.PomodoroTemplates.AnyAsync(t =>
                t.Id == request.PomodoroTemplateId.Value
            );

            if (!templateExists)
            {
                return new ApplicationError(
                    ErrorTypes.PomodoroTemplateNotFound,
                    $"Pomodoro template with id {request.PomodoroTemplateId} not found."
                );
            }
        }

        var todo = new Todo(
            title: request.Title,
            description: request.Description,
            recurrenceRule: rule.AsT1,
            notificationEnabled: request.NotificationEnabled
        )
        {
            PomodoroTemplateId = request.PomodoroTemplateId,
            UserId = userId,
            NotificationLeadTime = TimeSpan.FromMinutes(request.NotifyBeforeInMinutes),
        };

        try
        {
            await dbContext.Todos.AddAsync(todo);
            var saved = await dbContext.SaveChangesAsync();
            if (saved > 0)
            {
                // Enqueue background task to create initial occurrences
                queue.EnqueueBackgroundWorkItem(
                    async (sp, ct) =>
                    {
                        var scopeDbContext = sp.GetRequiredService<ApplicationDbContext>();
                        DateTimeOffset endDate;
                        if (request.RecurrenceRule.Until is not null)
                        {
                            endDate = request.RecurrenceRule.Until.Value;
                        }
                        else if (request.RecurrenceRule.Count is not null)
                        {
                            endDate = rule.AsT1.GetEffectiveEndDate()!.Value;
                        }
                        else
                        {
                            // Default to one year from start date if no end specified
                            endDate = request.RecurrenceRule.StartDate.AddYears(1);
                        }

                        var occurrences = todo
                            .RecurrenceRule.GetOccurrences(
                                request.RecurrenceRule.StartDate,
                                endDate
                            )
                            .Select(date => new TodoOccurrence
                            {
                                TodoId = todo.Id,
                                OccurrenceDate = date,
                            });

                        try
                        {
                            await scopeDbContext.TodoOccurrences.AddRangeAsync(occurrences, ct);
                            await scopeDbContext.SaveChangesAsync(ct);
                        }
                        catch (Exception e)
                        {
                            logger.LogError(
                                e,
                                "Error creating todo occurrences for recurring todo: {Message}",
                                e.Message
                            );
                        }
                    }
                );
            }
            return true;
        }
        catch (Exception e)
        {
            logger.LogError(e, "Error creating recurring todo: {Message}", e.Message);
            return new ApplicationError(
                ErrorTypes.DatabaseError,
                "Failed to create recurring todo."
            );
        }
    }
}
