using Kadans.Modules.Tasks.Contracts;
using Kadans.Modules.Tasks.Domain;
using Kadans.Modules.Tasks.Persistence;
using Kadans.SharedKernel.Errors;
using Kadans.SharedKernel.Realtime;
using Kadans.SharedKernel.Security;
using Kadans.SharedKernel.Users;
using Microsoft.EntityFrameworkCore;
using OneOf;
using TaskStatus = Kadans.Modules.Tasks.Domain.TaskStatus;

namespace Kadans.Modules.Tasks.Features.Pomodoro;

internal sealed class PomodoroService(
    TasksDbContext context,
    ICurrentUserService currentUser,
    IUserDirectory users,
    IRealtimePublisher realtime,
    ILogger<PomodoroService> logger
)
{
    public async Task<OneOf<ApplicationError, PomodoroTemplateResponse>> CreateTemplate(CreatePomodoroTemplate request)
    {
        var userId = currentUser.UserId;
        if (string.IsNullOrWhiteSpace(userId))
            return new ApplicationError(ErrorTypes.Unauthorized, "User is not authenticated.");

        if (string.IsNullOrWhiteSpace(request.Name))
            return new ApplicationError(ErrorTypes.PomodoroTemplateInvalid, "Template name is required.");

        if (request.Phases is not { Count: > 0 })
            return new ApplicationError(ErrorTypes.PomodoroTemplateInvalid, "At least one Pomodoro phase is required.");

        if (request.Phases.Any(p => p.DurationMinutes <= 0))
            return new ApplicationError(ErrorTypes.PomodoroTemplateInvalid, "Phase duration must be greater than zero.");

        var template = new PomodoroTemplate
        {
            Name = request.Name.Trim(),
            UserId = userId,
            Phases =
            [
                .. request.Phases.Select(
                    (phase, index) => new PomodoroTemplatePhase
                    {
                        Order = index,
                        Type = phase.Type,
                        DurationMinutes = phase.DurationMinutes,
                    }
                ),
            ],
        };

        context.PomodoroTemplates.Add(template);
        await context.SaveChangesAsync();
        return template.ToResponse();
    }

    public async Task<OneOf<ApplicationError, List<PomodoroTemplateResponse>>> GetTemplates()
    {
        var templates = await context
            .PomodoroTemplates.Include(t => t.Phases)
            .OrderByDescending(t => t.CreatedAt)
            .ToListAsync();

        return templates.ConvertAll(t => t.ToResponse());
    }

    public async Task<OneOf<ApplicationError, bool>> AttachTemplateToTodo(Guid todoId, Guid? templateId)
    {
        var todo = await context.Todos.FirstOrDefaultAsync(t => t.Id == todoId);
        if (todo is null)
            return new ApplicationError(ErrorTypes.TodoNotFound, $"Todo with id {todoId} not found");

        if (templateId is not null)
        {
            var template = await context
                .PomodoroTemplates.Include(t => t.Phases)
                .FirstOrDefaultAsync(t => t.Id == templateId.Value);

            if (template is null)
                return new ApplicationError(ErrorTypes.PomodoroTemplateNotFound, $"Pomodoro template with id {templateId} not found");

            if (template.Phases.Count == 0)
                return new ApplicationError(ErrorTypes.PomodoroTemplateInvalid, "Cannot attach an empty Pomodoro template.");
        }

        todo.PomodoroTemplateId = templateId;
        await context.SaveChangesAsync();
        return true;
    }

    public async Task<OneOf<ApplicationError, PomodoroRunResponse>> StartRun(Guid todoId, bool autoAdvance)
    {
        var userId = currentUser.UserId;
        if (string.IsNullOrWhiteSpace(userId))
            return new ApplicationError(ErrorTypes.Unauthorized, "User is not authenticated.");

        var todo = await context
            .Todos.Include(t => t.PomodoroTemplate)
                .ThenInclude(t => t!.Phases)
            .FirstOrDefaultAsync(t => t.Id == todoId);

        if (todo is null)
            return new ApplicationError(ErrorTypes.TodoNotFound, $"Todo with id {todoId} not found");

        if (todo.PomodoroTemplate is null)
            return new ApplicationError(ErrorTypes.PomodoroTemplateRequired, "This todo has no Pomodoro template attached.");

        if (todo.PomodoroTemplate.Phases.Count == 0)
            return new ApplicationError(ErrorTypes.PomodoroTemplateInvalid, "Cannot start a Pomodoro run from an empty template.");

        var hasActiveRun = await context.PomodoroRuns.AnyAsync(r =>
            r.TodoId == todoId && (r.Status == PomodoroRunStatus.Active || r.Status == PomodoroRunStatus.Paused)
        );
        if (hasActiveRun)
            return new ApplicationError(ErrorTypes.PomodoroAlreadyActiveForTodo, "This todo already has an active Pomodoro run.");

        var run = PomodoroRun.Start(todo, todo.PomodoroTemplate.Phases, userId, autoAdvance, DateTimeOffset.UtcNow);

        if (todo.Status == TaskStatus.Scheduled)
            todo.UpdateStatus(TaskStatus.Started);

        context.PomodoroRuns.Add(run);
        await context.SaveChangesAsync();
        logger.LogInformation("Started pomodoro run {RunId} on todo {TodoId} (autoAdvance: {AutoAdvance})", run.Id, todoId, autoAdvance);
        return await PublishAsync(run);
    }

    public async Task<OneOf<ApplicationError, PomodoroRunResponse>> GetActiveRun(Guid todoId)
    {
        var run = await context
            .PomodoroRuns.Include(r => r.Phases)
            .Where(r => r.TodoId == todoId && (r.Status == PomodoroRunStatus.Active || r.Status == PomodoroRunStatus.Paused))
            .OrderByDescending(r => r.StartedAt)
            .FirstOrDefaultAsync();

        if (run is null)
            return new ApplicationError(ErrorTypes.PomodoroRunNotFound, "No active Pomodoro run found for this todo.");

        return run.ToResponse();
    }

    public async Task<OneOf<ApplicationError, List<PomodoroRunResponse>>> GetRunHistory(Guid todoId, int page = 1, int pageSize = 20)
    {
        var runs = await context
            .PomodoroRuns.Include(r => r.Phases)
            .Where(r => r.TodoId == todoId)
            .OrderByDescending(r => r.StartedAt)
            .Skip((page - 1) * pageSize)
            .Take(pageSize)
            .ToListAsync();

        return runs.ConvertAll(r => r.ToResponse());
    }

    /// <summary>Completed-phase minutes and run counts, grouped per day in the user's time zone.</summary>
    public async Task<OneOf<ApplicationError, PomodoroStatsResponse>> GetStats(DateTimeOffset? from, DateTimeOffset? to)
    {
        var userId = currentUser.UserId;
        if (string.IsNullOrWhiteSpace(userId))
            return new ApplicationError(ErrorTypes.Unauthorized, "User is not authenticated.");

        var rangeTo = to ?? DateTimeOffset.UtcNow;
        var rangeFrom = from ?? rangeTo.AddDays(-7);
        if (rangeFrom > rangeTo || rangeTo - rangeFrom > TimeSpan.FromDays(366))
            return new ApplicationError(ErrorTypes.InvalidInterval, "The stats range must be positive and at most a year.");

        var user = await users.FindAsync(userId);
        var timeZone = user is not null && TimeZoneInfo.TryFindSystemTimeZoneById(user.TimeZoneId, out var found)
            ? found
            : TimeZoneInfo.Utc;

        var phases = await context
            .PomodoroRuns.SelectMany(r => r.Phases)
            .Where(p => p.CompletedAt != null && p.CompletedAt >= rangeFrom && p.CompletedAt <= rangeTo)
            .Select(p => new { p.Type, p.DurationMinutes, CompletedAt = p.CompletedAt!.Value })
            .ToListAsync();

        var completedRuns = await context
            .PomodoroRuns.Where(r => r.Status == PomodoroRunStatus.Completed && r.CompletedAt >= rangeFrom && r.CompletedAt <= rangeTo)
            .Select(r => r.CompletedAt!.Value)
            .ToListAsync();

        var cancelledRuns = await context.PomodoroRuns.CountAsync(r =>
            r.Status == PomodoroRunStatus.Cancelled && r.CompletedAt >= rangeFrom && r.CompletedAt <= rangeTo
        );

        DateOnly LocalDate(DateTimeOffset at) => DateOnly.FromDateTime(TimeZoneInfo.ConvertTime(at, timeZone).Date);

        var perDay = phases
            .GroupBy(p => LocalDate(p.CompletedAt))
            .Select(g => new PomodoroDayStats(
                g.Key,
                g.Where(p => p.Type == PomodoroPhaseType.Focus).Sum(p => p.DurationMinutes),
                g.Where(p => p.Type == PomodoroPhaseType.Break).Sum(p => p.DurationMinutes),
                completedRuns.Count(c => LocalDate(c) == g.Key)
            ))
            .OrderBy(d => d.Date)
            .ToList();

        return new PomodoroStatsResponse(
            rangeFrom,
            rangeTo,
            timeZone.Id,
            completedRuns.Count,
            cancelledRuns,
            phases.Where(p => p.Type == PomodoroPhaseType.Focus).Sum(p => p.DurationMinutes),
            phases.Where(p => p.Type == PomodoroPhaseType.Break).Sum(p => p.DurationMinutes),
            perDay
        );
    }

    public Task<OneOf<ApplicationError, PomodoroRunResponse>> PauseRun(Guid runId) =>
        MutateAsync(runId, (run, now) => run.Pause(now));

    public Task<OneOf<ApplicationError, PomodoroRunResponse>> ResumeRun(Guid runId) =>
        MutateAsync(runId, (run, now) => run.Resume(now));

    public Task<OneOf<ApplicationError, PomodoroRunResponse>> AdvanceRun(Guid runId, AdvancePomodoroRun request) =>
        MutateAsync(runId, (run, now) => run.Advance(request.ExpectedPhaseIndex, now));

    public Task<OneOf<ApplicationError, PomodoroRunResponse>> CancelRun(Guid runId) =>
        MutateAsync(runId, (run, now) => run.Cancel(now));

    private async Task<OneOf<ApplicationError, PomodoroRunResponse>> MutateAsync(
        Guid runId,
        Func<PomodoroRun, DateTimeOffset, OneOf<ApplicationError, OneOf.Types.Success>> mutate
    )
    {
        var run = await context
            .PomodoroRuns.Include(r => r.Phases)
            .FirstOrDefaultAsync(r => r.Id == runId);

        if (run is null)
            return new ApplicationError(ErrorTypes.PomodoroRunNotFound, $"Pomodoro run with id {runId} not found");

        var result = mutate(run, DateTimeOffset.UtcNow);
        if (result.IsT0)
            return result.AsT0;

        await context.SaveChangesAsync();
        return await PublishAsync(run);
    }

    /// <summary>Every device of the user sees the same run state; the API is the source of truth.</summary>
    private async Task<PomodoroRunResponse> PublishAsync(PomodoroRun run)
    {
        var response = run.ToResponse();
        try
        {
            await realtime.PublishToUserAsync(run.UserId, "pomodoro.run.changed", response);
        }
        catch (Exception ex)
        {
            logger.LogWarning(ex, "Could not broadcast run {RunId}", run.Id);
        }

        return response;
    }
}
