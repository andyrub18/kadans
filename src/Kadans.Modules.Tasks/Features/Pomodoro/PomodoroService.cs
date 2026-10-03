using Kadans.Modules.Tasks.Contracts;
using Kadans.Modules.Tasks.Domain;
using Kadans.Modules.Tasks.Persistence;
using Kadans.SharedKernel.Errors;
using Kadans.SharedKernel.Http;
using Kadans.SharedKernel.Notifications;
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
    INotificationDispatcher dispatcher,
    PomodoroDeadlineSignal deadlines,
    ILogger<PomodoroService> logger
)
{
    public async Task<OneOf<ApplicationError, PomodoroTemplateResponse>> CreateTemplate(CreatePomodoroTemplate request)
    {
        var userId = currentUser.UserId;
        if (string.IsNullOrWhiteSpace(userId))
            return new ApplicationError(ErrorTypes.Unauthorized, "User is not authenticated.");

        if (ValidateTemplate(request) is { } invalid)
            return invalid;

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

    /// <summary>Replaces name and phases. Runs snapshot their phases at start, so past and active runs keep theirs.</summary>
    public async Task<OneOf<ApplicationError, PomodoroTemplateResponse>> UpdateTemplate(Guid templateId, CreatePomodoroTemplate request)
    {
        if (ValidateTemplate(request) is { } invalid)
            return invalid;

        var template = await context
            .PomodoroTemplates.Include(t => t.Phases)
            .FirstOrDefaultAsync(t => t.Id == templateId);

        if (template is null)
            return new ApplicationError(ErrorTypes.PomodoroTemplateNotFound, $"Pomodoro template with id {templateId} not found");

        context.PomodoroTemplatePhases.RemoveRange(template.Phases);
        template.Name = request.Name.Trim();
        template.UpdatedAt = DateTimeOffset.UtcNow;
        template.Phases = request.Phases
            .Select((phase, index) => new PomodoroTemplatePhase
            {
                PomodoroTemplateId = template.Id,
                Order = index,
                Type = phase.Type,
                DurationMinutes = phase.DurationMinutes,
            })
            .ToList();
        // Client-set GUID keys on a tracked parent: fixup would guess "existing" — mark explicitly.
        context.PomodoroTemplatePhases.AddRange(template.Phases);

        await context.SaveChangesAsync();
        return template.ToResponse();
    }

    /// <summary>Todos referencing it fall back to no template (FK set-null); runs keep their snapshots.</summary>
    public async Task<OneOf<ApplicationError, bool>> DeleteTemplate(Guid templateId)
    {
        var template = await context.PomodoroTemplates.FirstOrDefaultAsync(t => t.Id == templateId);
        if (template is null)
            return new ApplicationError(ErrorTypes.PomodoroTemplateNotFound, $"Pomodoro template with id {templateId} not found");

        context.PomodoroTemplates.Remove(template);
        await context.SaveChangesAsync();
        return true;
    }

    private static ApplicationError? ValidateTemplate(CreatePomodoroTemplate request)
    {
        if (string.IsNullOrWhiteSpace(request.Name))
            return new ApplicationError(ErrorTypes.PomodoroTemplateInvalid, "Template name is required.");

        if (request.Phases is not { Count: > 0 })
            return new ApplicationError(ErrorTypes.PomodoroTemplateInvalid, "At least one Pomodoro phase is required.");

        if (request.Phases.Count > PomodoroTemplate.MaxPhases)
            return new ApplicationError(ErrorTypes.PomodoroTemplateInvalid, "A cycle has at most 24 phases.");

        if (request.Phases.Any(p => p.DurationMinutes is < 1 or > PomodoroTemplate.MaxPhaseMinutes))
            return new ApplicationError(ErrorTypes.PomodoroTemplateInvalid, "Each phase lasts 1 to 240 minutes.");

        return null;
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

    /// <param name="finishAt">When the session ends by itself; null: <see cref="PomodoroRun.DefaultSpan"/> after the start.</param>
    public async Task<OneOf<ApplicationError, PomodoroRunResponse>> StartRun(Guid todoId, bool autoAdvance, bool loop, DateTimeOffset? finishAt = null)
    {
        var userId = currentUser.UserId;
        if (string.IsNullOrWhiteSpace(userId))
            return new ApplicationError(ErrorTypes.Unauthorized, "User is not authenticated.");

        var now = DateTimeOffset.UtcNow;
        if (finishAt is { } end && PomodoroRun.CheckFinishBy(end, now) is { } endError)
            return endError;

        var todo = await context
            .Todos.Include(t => t.PomodoroTemplate)
                .ThenInclude(t => t!.Phases)
            // Template phases + the todo's owned remarks are two collection loads: split them.
            .AsSplitQuery()
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

        var run = PomodoroRun.Start(todo, todo.PomodoroTemplate.Phases, userId, autoAdvance, now, loop, finishAt);

        if (todo.Status == TaskStatus.Scheduled)
            todo.UpdateStatus(TaskStatus.Started);

        context.PomodoroRuns.Add(run);
        await context.SaveChangesAsync();
        logger.LogInformation("Started pomodoro run {RunId} on todo {TodoId} (autoAdvance: {AutoAdvance})", run.Id, todoId, autoAdvance);
        deadlines.Pulse(); // a new deadline exists: the watcher may have to wake up sooner
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
        if (Paging.Check(page, pageSize) is { } pagingError)
            return pagingError;

        var runs = await context
            .PomodoroRuns.Include(r => r.Phases)
            .Where(r => r.TodoId == todoId)
            .OrderByDescending(r => r.StartedAt)
            .Skip((page - 1) * pageSize)
            .Take(pageSize)
            .ToListAsync();

        return runs.ConvertAll(r => r.ToResponse());
    }

    /// <summary>
    /// Minutes really spent in ended phases (skipped early, kept going past the timer, pauses left out), and run
    /// counts, grouped per day in the user's time zone.
    /// </summary>
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
            .Select(p => new { p.Type, p.StartedAt, p.CompletedAt, p.PausedSeconds })
            .ToListAsync();
        var spent = phases
            .Select(p => new
            {
                p.Type,
                Seconds = PomodoroRunPhase.SecondsSpent(p.StartedAt, p.CompletedAt, p.PausedSeconds) ?? 0,
                CompletedAt = p.CompletedAt!.Value,
            })
            .ToList();
        static int Minutes(IEnumerable<int> seconds) => seconds.Sum() / 60;

        var completedRuns = await context
            .PomodoroRuns.Where(r => r.Status == PomodoroRunStatus.Completed && r.CompletedAt >= rangeFrom && r.CompletedAt <= rangeTo)
            .Select(r => r.CompletedAt!.Value)
            .ToListAsync();

        var cancelledRuns = await context.PomodoroRuns.CountAsync(r =>
            r.Status == PomodoroRunStatus.Cancelled && r.CompletedAt >= rangeFrom && r.CompletedAt <= rangeTo
        );

        DateOnly LocalDate(DateTimeOffset at) => DateOnly.FromDateTime(TimeZoneInfo.ConvertTime(at, timeZone).Date);

        var perDay = spent
            .GroupBy(p => LocalDate(p.CompletedAt))
            .Select(g => new PomodoroDayStats(
                g.Key,
                Minutes(g.Where(p => p.Type == PomodoroPhaseType.Focus).Select(p => p.Seconds)),
                Minutes(g.Where(p => p.Type == PomodoroPhaseType.Break).Select(p => p.Seconds)),
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
            Minutes(spent.Where(p => p.Type == PomodoroPhaseType.Focus).Select(p => p.Seconds)),
            Minutes(spent.Where(p => p.Type == PomodoroPhaseType.Break).Select(p => p.Seconds)),
            perDay
        );
    }

    public Task<OneOf<ApplicationError, PomodoroRunResponse>> PauseRun(Guid runId) =>
        MutateAsync(runId, (run, now) => run.Pause(now));

    public Task<OneOf<ApplicationError, PomodoroRunResponse>> ResumeRun(Guid runId) =>
        MutateAsync(runId, (run, now) => run.Resume(now));

    public Task<OneOf<ApplicationError, PomodoroRunResponse>> ChangeFinishAt(Guid runId, ChangePomodoroFinishAt request) =>
        MutateAsync(runId, (run, now) => run.ChangeFinishBy(request.FinishAt, now));

    public async Task<OneOf<ApplicationError, PomodoroRunResponse>> AdvanceRun(Guid runId, AdvancePomodoroRun request)
    {
        // The app's "this phase ran out" counts only when the server's clock agrees. Too early (a device clock
        // running fast), or a manual run, which waits for the person: nothing changes, nobody is notified, and the
        // app keeps showing the run as it is. A run that is no longer active falls through to the usual error.
        if (request.OnlyIfEnded)
        {
            var current = await context.PomodoroRuns.AsNoTracking().Include(r => r.Phases).FirstOrDefaultAsync(r => r.Id == runId);
            if (current is { Status: PomodoroRunStatus.Active } && !current.DeadlineReached(DateTimeOffset.UtcNow))
                return current.ToResponse();
        }

        var result = await MutateAsync(runId, (run, now) => run.Advance(request.ExpectedPhaseIndex, now));
        // Hands-free phase changes notify every device, no matter who won the advance — this
        // request or the deadline watcher; the row version guarantees it is exactly one of them.
        // Manual runs stay silent: the user did it.
        if (result.IsT1 && result.AsT1.AutoAdvance)
            await NotifyPhaseChange(result.AsT1);
        return result;
    }

    private async Task NotifyPhaseChange(PomodoroRunResponse run)
    {
        try
        {
            var userId = currentUser.UserId!;
            var language = (await users.FindAsync(userId))?.Language ?? "en";
            var texts = LocalizedTexts.Pomodoro(language);
            var title = await context
                .Todos.Where(t => t.Id == run.TodoId)
                .Select(t => t.Title)
                .FirstOrDefaultAsync() ?? "Kadans";
            var phase = run.Phases[run.CurrentPhaseIndex];
            var body = PomodoroAutoAdvancer.PhaseBody(
                texts, run.Status, phase.Type, phase.DurationMinutes,
                run.CurrentPhaseIndex, run.CycleLength, run.Loop
            );
            await dispatcher.DispatchAsync(
                userId,
                new NotificationMessage(
                    PomodoroAutoAdvancer.Kind,
                    title,
                    body,
                    new Dictionary<string, string>
                    {
                        ["todoId"] = run.TodoId.ToString(),
                        ["runId"] = run.Id.ToString(),
                        ["status"] = run.Status.ToString(),
                        ["currentPhaseIndex"] = run.CurrentPhaseIndex.ToString(),
                    }
                )
            );
        }
        catch (Exception ex)
        {
            logger.LogWarning(ex, "Could not notify phase change for run {RunId}", run.Id);
        }
    }

    public Task<OneOf<ApplicationError, PomodoroRunResponse>> FinishRun(Guid runId) =>
        MutateAsync(runId, (run, now) => run.Finish(now));

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

        var existingPhaseIds = run.Phases.Select(p => p.Id).ToHashSet();
        var result = mutate(run, DateTimeOffset.UtcNow);
        if (result.IsT0)
            return result.AsT0;

        MarkNewPhasesAdded(context, run, existingPhaseIds);
        try
        {
            await context.SaveChangesAsync();
        }
        catch (DbUpdateConcurrencyException)
        {
            // Someone else (the deadline watcher, another device) changed the run between our read and
            // our write. Same answer as a stale expectedPhaseIndex: the client resyncs and sees where it is.
            return new ApplicationError(
                ErrorTypes.PomodoroRunInvalidState,
                "The run changed at the same moment. Refresh run state and retry."
            );
        }

        deadlines.Pulse(); // resume/advance moved the deadline; pause/finish/cancel removed it
        return await PublishAsync(run);
    }

    /// <summary>
    /// A looping Advance appends lap phases to an already-tracked run. Their GUID keys are set
    /// client-side, so EF's navigation fixup would guess "existing row" and issue an UPDATE that
    /// hits nothing (DbUpdateConcurrencyException). Mark what the mutation created as Added.
    /// </summary>
    internal static void MarkNewPhasesAdded(TasksDbContext context, PomodoroRun run, ISet<Guid> existingPhaseIds)
    {
        foreach (var phase in run.Phases.Where(p => !existingPhaseIds.Contains(p.Id)))
            context.Entry(phase).State = EntityState.Added;
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
