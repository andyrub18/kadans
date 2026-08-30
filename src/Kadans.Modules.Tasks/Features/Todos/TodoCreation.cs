using Kadans.Modules.Tasks.Contracts;
using Kadans.Modules.Tasks.Domain;
using Kadans.Modules.Tasks.Features.Todos.Occurrences;
using Kadans.Modules.Tasks.Persistence;
using Kadans.SharedKernel.Errors;
using Kadans.SharedKernel.Security;
using Microsoft.EntityFrameworkCore;
using OneOf;

namespace Kadans.Modules.Tasks.Features.Todos;

internal sealed class TodoCreation(
    TasksDbContext dbContext,
    OccurrenceGenerator generator,
    ICurrentUserService currentUserService,
    ILogger<TodoCreation> logger
)
{
    public async Task<OneOf<ApplicationError, TodoResponse>> CreateOneTimeTodo(CreateOneTimeTodo request)
    {
        var validationResult = await new CreateOneTimeTodoRulesValidator().ValidateAsync(request);
        if (!validationResult.IsValid)
            return validationResult.ToValidationError("Validation failed for creating one-time todo.");

        var rule = RecurrenceRule.CreateOneTimeRule(request.DueDate);
        if (rule.IsT0)
            return rule.AsT0;

        return await CreateAsync(
            request.Title,
            request.Description,
            request.NotificationEnabled,
            request.NotifyBeforeInMinutes,
            request.PomodoroTemplateId,
            rule.AsT1
        );
    }

    public async Task<OneOf<ApplicationError, TodoResponse>> CreateRecurringTodo(CreateRecurringTodo request)
    {
        var validationResult = await new CreateRecurringTodoRulesValidator().ValidateAsync(request);
        if (!validationResult.IsValid)
            return validationResult.ToValidationError("Validation failed for creating recurring todo.");

        var rule = request.RecurrenceRule.ToDomainRule();
        if (rule.IsT0)
            return rule.AsT0;

        return await CreateAsync(
            request.Title,
            request.Description,
            request.NotificationEnabled,
            request.NotifyBeforeInMinutes,
            request.PomodoroTemplateId,
            rule.AsT1
        );
    }

    private async Task<OneOf<ApplicationError, TodoResponse>> CreateAsync(
        string title,
        string description,
        bool notificationEnabled,
        uint notifyBeforeInMinutes,
        Guid? pomodoroTemplateId,
        RecurrenceRule rule
    )
    {
        var userId = currentUserService.UserId;
        if (userId is null)
            return new ApplicationError(ErrorTypes.Unauthorized, "User must be authenticated to create a todo.");

        if (pomodoroTemplateId is not null && !await dbContext.PomodoroTemplates.AnyAsync(t => t.Id == pomodoroTemplateId.Value))
            return new ApplicationError(ErrorTypes.PomodoroTemplateNotFound, $"Pomodoro template with id {pomodoroTemplateId} not found.");

        var todo = new Todo(title, description, rule, notificationEnabled)
        {
            PomodoroTemplateId = pomodoroTemplateId,
            UserId = userId,
            NotificationLeadTime = TimeSpan.FromMinutes(notifyBeforeInMinutes),
        };

        try
        {
            dbContext.Todos.Add(todo);
            var generated = await generator.EnsureGeneratedAsync(todo, DateTimeOffset.UtcNow);
            await dbContext.SaveChangesAsync();

            logger.LogInformation("Created todo {TodoId} with {Count} occurrence(s) materialized", todo.Id, generated);
            return todo.ToResponse();
        }
        catch (Exception e)
        {
            logger.LogError(e, "Error creating todo");
            return new ApplicationError(ErrorTypes.DatabaseError, "Failed to create todo.");
        }
    }
}
