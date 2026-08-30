using Kadans.SharedKernel.Security;
using Kadans.Api.Contracts;
using Kadans.Api.Data;
using Kadans.Api.DTOs;
using Kadans.SharedKernel.Errors;
using Kadans.Api.Models;
using Kadans.Api.Security;
using Microsoft.EntityFrameworkCore;
using OneOf;
using TaskStatus = Kadans.Api.Models.TaskStatus;

namespace Kadans.Api.Services;

public sealed class PomodoroService(
    ApplicationDbContext context,
    ICurrentUserService currentUser,
    ILogger<PomodoroService> logger
)
{
    public async Task<OneOf<ApplicationError, PomodoroTemplateResponse>> CreateTemplate(
        CreatePomodoroTemplate request
    )
    {
        var userId = currentUser.UserId;
        if (string.IsNullOrWhiteSpace(userId))
        {
            return new ApplicationError(ErrorTypes.Unauthorized, "User is not authenticated.");
        }

        if (string.IsNullOrWhiteSpace(request.Name))
        {
            return new ApplicationError(
                ErrorTypes.PomodoroTemplateInvalid,
                "Template name is required."
            );
        }

        if (request.Phases is null || request.Phases.Count == 0)
        {
            return new ApplicationError(
                ErrorTypes.PomodoroTemplateInvalid,
                "At least one Pomodoro phase is required."
            );
        }

        if (request.Phases.Any(p => p.DurationMinutes <= 0))
        {
            return new ApplicationError(
                ErrorTypes.PomodoroTemplateInvalid,
                "Phase duration must be greater than zero."
            );
        }

        var template = new PomodoroTemplate
        {
            Name = request.Name.Trim(),
            UserId = userId,
            Phases =
            [
                .. request.Phases.Select(
                    (phase, index) =>
                        new PomodoroTemplatePhase
                        {
                            Order = index,
                            Type = phase.Type,
                            DurationMinutes = phase.DurationMinutes,
                        }
                ),
            ],
        };

        try
        {
            await context.PomodoroTemplates.AddAsync(template);
            await context.SaveChangesAsync();
            return template.ToResponse();
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Failed to create Pomodoro template");
            return new ApplicationError(
                ErrorTypes.DatabaseError,
                "Failed to create Pomodoro template."
            );
        }
    }

    public async Task<OneOf<ApplicationError, List<PomodoroTemplateResponse>>> GetTemplates()
    {
        try
        {
            var templates = await context
                .PomodoroTemplates.Include(t => t.Phases)
                .OrderByDescending(t => t.CreatedAt)
                .ToListAsync();

            return templates.ConvertAll(t => t.ToResponse());
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Failed to retrieve Pomodoro templates");
            return new ApplicationError(
                ErrorTypes.DatabaseError,
                "Failed to retrieve Pomodoro templates."
            );
        }
    }

    public async Task<OneOf<ApplicationError, bool>> AttachTemplateToTodo(
        Guid todoId,
        Guid? templateId
    )
    {
        var todo = await context.Todos.FirstOrDefaultAsync(t => t.Id == todoId);
        if (todo is null)
        {
            return new ApplicationError(
                ErrorTypes.TodoNotFound,
                $"Todo with id {todoId} not found"
            );
        }

        if (templateId is not null)
        {
            var template = await context
                .PomodoroTemplates.Include(t => t.Phases)
                .FirstOrDefaultAsync(t => t.Id == templateId.Value);

            if (template is null)
            {
                return new ApplicationError(
                    ErrorTypes.PomodoroTemplateNotFound,
                    $"Pomodoro template with id {templateId} not found"
                );
            }

            if (template.Phases.Count == 0)
            {
                return new ApplicationError(
                    ErrorTypes.PomodoroTemplateInvalid,
                    "Cannot attach an empty Pomodoro template."
                );
            }
        }

        todo.PomodoroTemplateId = templateId;

        try
        {
            await context.SaveChangesAsync();
            return true;
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Failed to attach Pomodoro template to todo {TodoId}", todoId);
            return new ApplicationError(
                ErrorTypes.DatabaseError,
                "Failed to attach Pomodoro template to todo."
            );
        }
    }

    public async Task<OneOf<ApplicationError, PomodoroRunResponse>> StartRun(Guid todoId)
    {
        var userId = currentUser.UserId;
        if (string.IsNullOrWhiteSpace(userId))
        {
            return new ApplicationError(ErrorTypes.Unauthorized, "User is not authenticated.");
        }

        var todo = await context
            .Todos.Include(t => t.PomodoroTemplate)
                .ThenInclude(t => t!.Phases)
            .FirstOrDefaultAsync(t => t.Id == todoId);

        if (todo is null)
        {
            return new ApplicationError(
                ErrorTypes.TodoNotFound,
                $"Todo with id {todoId} not found"
            );
        }

        if (todo.PomodoroTemplate is null)
        {
            return new ApplicationError(
                ErrorTypes.PomodoroTemplateRequired,
                "This todo has no Pomodoro template attached."
            );
        }

        var hasActiveRun = await context.PomodoroRuns.AnyAsync(r =>
            r.TodoId == todoId
            && (r.Status == PomodoroRunStatus.Active || r.Status == PomodoroRunStatus.Paused)
        );

        if (hasActiveRun)
        {
            return new ApplicationError(
                ErrorTypes.PomodoroAlreadyActiveForTodo,
                "This todo already has an active Pomodoro run."
            );
        }

        var orderedPhases = todo.PomodoroTemplate.Phases.OrderBy(p => p.Order).ToList();
        if (orderedPhases.Count == 0)
        {
            return new ApplicationError(
                ErrorTypes.PomodoroTemplateInvalid,
                "Cannot start a Pomodoro run from an empty template."
            );
        }

        var run = new PomodoroRun
        {
            TodoId = todo.Id,
            PomodoroTemplateId = todo.PomodoroTemplateId,
            UserId = userId,
            CurrentPhaseIndex = 0,
            Phases = orderedPhases.ConvertAll(p => new PomodoroRunPhase
            {
                Order = p.Order,
                Type = p.Type,
                DurationMinutes = p.DurationMinutes,
                StartedAt = p.Order == 0 ? DateTimeOffset.UtcNow : null,
            }),
        };

        if (todo.Status == TaskStatus.Scheduled)
        {
            todo.UpdateStatus(TaskStatus.Started);
        }

        try
        {
            await context.PomodoroRuns.AddAsync(run);
            await context.SaveChangesAsync();
            return run.ToResponse();
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Failed to start Pomodoro run for todo {TodoId}", todoId);
            return new ApplicationError(ErrorTypes.DatabaseError, "Failed to start Pomodoro run.");
        }
    }

    public async Task<OneOf<ApplicationError, PomodoroRunResponse>> GetActiveRun(Guid todoId)
    {
        var run = await context
            .PomodoroRuns.Include(r => r.Phases)
            .Where(r =>
                r.TodoId == todoId
                && (r.Status == PomodoroRunStatus.Active || r.Status == PomodoroRunStatus.Paused)
            )
            .OrderByDescending(r => r.StartedAt)
            .FirstOrDefaultAsync();

        if (run is null)
        {
            return new ApplicationError(
                ErrorTypes.PomodoroRunNotFound,
                "No active Pomodoro run found for this todo."
            );
        }

        return run.ToResponse();
    }

    public async Task<OneOf<ApplicationError, PomodoroRunResponse>> PauseRun(Guid runId)
    {
        var run = await context
            .PomodoroRuns.Include(r => r.Phases)
            .FirstOrDefaultAsync(r => r.Id == runId);

        if (run is null)
        {
            return new ApplicationError(
                ErrorTypes.PomodoroRunNotFound,
                $"Pomodoro run with id {runId} not found"
            );
        }

        if (run.Status != PomodoroRunStatus.Active)
        {
            return new ApplicationError(
                ErrorTypes.PomodoroRunInvalidState,
                "Only active runs can be paused."
            );
        }

        run.Pause();

        try
        {
            await context.SaveChangesAsync();
            return run.ToResponse();
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Failed to pause Pomodoro run {RunId}", runId);
            return new ApplicationError(ErrorTypes.DatabaseError, "Failed to pause Pomodoro run.");
        }
    }

    public async Task<OneOf<ApplicationError, PomodoroRunResponse>> ResumeRun(Guid runId)
    {
        var run = await context
            .PomodoroRuns.Include(r => r.Phases)
            .FirstOrDefaultAsync(r => r.Id == runId);

        if (run is null)
        {
            return new ApplicationError(
                ErrorTypes.PomodoroRunNotFound,
                $"Pomodoro run with id {runId} not found"
            );
        }

        if (run.Status != PomodoroRunStatus.Paused)
        {
            return new ApplicationError(
                ErrorTypes.PomodoroRunInvalidState,
                "Only paused runs can be resumed."
            );
        }

        run.Resume();

        var currentPhase = run.Phases.FirstOrDefault(p => p.Order == run.CurrentPhaseIndex);
        if (currentPhase is not null && currentPhase.StartedAt is null)
        {
            currentPhase.StartedAt = DateTimeOffset.UtcNow;
        }

        try
        {
            await context.SaveChangesAsync();
            return run.ToResponse();
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Failed to resume Pomodoro run {RunId}", runId);
            return new ApplicationError(ErrorTypes.DatabaseError, "Failed to resume Pomodoro run.");
        }
    }

    public async Task<OneOf<ApplicationError, PomodoroRunResponse>> AdvanceRun(
        Guid runId,
        AdvancePomodoroRun request
    )
    {
        var run = await context
            .PomodoroRuns.Include(r => r.Phases)
            .FirstOrDefaultAsync(r => r.Id == runId);

        if (run is null)
        {
            return new ApplicationError(
                ErrorTypes.PomodoroRunNotFound,
                $"Pomodoro run with id {runId} not found"
            );
        }

        if (run.Status != PomodoroRunStatus.Active)
        {
            return new ApplicationError(
                ErrorTypes.PomodoroRunInvalidState,
                "Only active runs can advance phases."
            );
        }

        var orderedPhases = run.Phases.OrderBy(p => p.Order).ToList();
        if (orderedPhases.Count == 0)
        {
            return new ApplicationError(
                ErrorTypes.PomodoroRunInvalidState,
                "Pomodoro run has no phases."
            );
        }

        if (run.CurrentPhaseIndex >= orderedPhases.Count)
        {
            return new ApplicationError(
                ErrorTypes.PomodoroRunInvalidState,
                "Pomodoro run is already completed."
            );
        }

        if (
            request.ExpectedPhaseIndex is not null
            && request.ExpectedPhaseIndex.Value != run.CurrentPhaseIndex
        )
        {
            return new ApplicationError(
                ErrorTypes.PomodoroRunInvalidState,
                "Run phase index mismatch. Refresh run state and retry."
            );
        }

        var now = DateTimeOffset.UtcNow;
        var currentPhase = orderedPhases[run.CurrentPhaseIndex];

        currentPhase.StartedAt ??= now;

        currentPhase.CompletedAt = now;

        var isLastPhase = run.CurrentPhaseIndex == orderedPhases.Count - 1;
        if (isLastPhase)
        {
            run.Complete();
        }
        else
        {
            run.CurrentPhaseIndex++;
            var nextPhase = orderedPhases[run.CurrentPhaseIndex];
            nextPhase.StartedAt ??= now;

            run.UpdatedAt = now;
        }

        try
        {
            await context.SaveChangesAsync();
            run.Phases = orderedPhases;
            return run.ToResponse();
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Failed to advance Pomodoro run {RunId}", runId);
            return new ApplicationError(
                ErrorTypes.DatabaseError,
                "Failed to advance Pomodoro run phase."
            );
        }
    }

    public async Task<OneOf<ApplicationError, PomodoroRunResponse>> CancelRun(Guid runId)
    {
        var run = await context
            .PomodoroRuns.Include(r => r.Phases)
            .FirstOrDefaultAsync(r => r.Id == runId);

        if (run is null)
        {
            return new ApplicationError(
                ErrorTypes.PomodoroRunNotFound,
                $"Pomodoro run with id {runId} not found"
            );
        }

        if (run.Status == PomodoroRunStatus.Completed || run.Status == PomodoroRunStatus.Cancelled)
        {
            return new ApplicationError(
                ErrorTypes.PomodoroRunInvalidState,
                "Only active or paused runs can be cancelled."
            );
        }

        run.Cancel();

        try
        {
            await context.SaveChangesAsync();
            return run.ToResponse();
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Failed to cancel Pomodoro run {RunId}", runId);
            return new ApplicationError(ErrorTypes.DatabaseError, "Failed to cancel Pomodoro run.");
        }
    }
}
