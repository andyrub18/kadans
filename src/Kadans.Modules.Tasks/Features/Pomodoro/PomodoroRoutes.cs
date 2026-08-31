using Kadans.Modules.Tasks.Contracts;
using Microsoft.AspNetCore.Mvc;
using Kadans.Modules.Tasks.Features.Todos;
using Kadans.Modules.Tasks.Features.Pomodoro;
using Microsoft.AspNetCore.Http.HttpResults;
using OneOf.Types;

namespace Kadans.Modules.Tasks.Features.Pomodoro;

internal static class PomodoroRoutes
{
    extension(IEndpointRouteBuilder app)
    {
        public void MapPomodoroRoutes()
        {
            var group = app.MapGroup(string.Empty).RequireAuthorization();

            group.MapPost(
                    "/pomodoro/templates",
                    async Task<Results<Ok<PomodoroTemplateResponse>, ProblemHttpResult>> (
                        CreatePomodoroTemplate request,
                        PomodoroService service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.CreateTemplate(request);
                        return result.Match<Results<Ok<PomodoroTemplateResponse>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            template => TypedResults.Ok(template)
                        );
                    }
                )
                .WithTags("Pomodoro")
                .WithName("PomodoroCreateTemplate")
                .WithSummary("Create Pomodoro template")
                .WithDescription("Creates a custom Pomodoro template with ordered phases.")
                .Produces<PomodoroTemplateResponse>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status401Unauthorized)
                .ProducesProblem(StatusCodes.Status500InternalServerError);

            group.MapGet(
                    "/pomodoro/templates",
                    async Task<Results<Ok<List<PomodoroTemplateResponse>>, ProblemHttpResult>> (
                        PomodoroService service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.GetTemplates();
                        return result.Match<Results<Ok<List<PomodoroTemplateResponse>>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            templates => TypedResults.Ok(templates)
                        );
                    }
                )
                .WithTags("Pomodoro")
                .WithName("PomodoroListTemplates")
                .WithSummary("List Pomodoro templates")
                .WithDescription("Returns all Pomodoro templates for the current user.")
                .Produces<List<PomodoroTemplateResponse>>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status500InternalServerError);

            group.MapPut(
                    "/todos/{id:guid}/pomodoro-template",
                    async Task<Results<Ok<Success>, ProblemHttpResult>> (
                        Guid id,
                        UpdateTodoPomodoro request,
                        PomodoroService service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.AttachTemplateToTodo(
                            id,
                            request.PomodoroTemplateId
                        );
                        return result.Match<Results<Ok<Success>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            _ => TypedResults.Ok(new Success())
                        );
                    }
                )
                .WithTags("Pomodoro", "Todos")
                .WithName("PomodoroAttachTemplateToTodo")
                .WithSummary("Attach Pomodoro template to todo")
                .WithDescription("Attaches or detaches a Pomodoro template on a todo.")
                .Produces<Success>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status404NotFound)
                .ProducesProblem(StatusCodes.Status400BadRequest);

            group.MapPost(
                    "/todos/{id:guid}/pomodoro/start",
                    async Task<Results<Ok<PomodoroRunResponse>, ProblemHttpResult>> (
                        Guid id,
                        PomodoroService service,
                        HttpContext context,
                        [FromQuery] bool autoAdvance = false
                    ) =>
                    {
                        var result = await service.StartRun(id, autoAdvance);
                        return result.Match<Results<Ok<PomodoroRunResponse>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            run => TypedResults.Ok(run)
                        );
                    }
                )
                .WithTags("Pomodoro", "Todos")
                .WithName("PomodoroStartRun")
                .WithSummary("Start Pomodoro run")
                .WithDescription("Starts a run from the attached template. With ?autoAdvance=true the server advances phases as they run out and notifies.")
                .Produces<PomodoroRunResponse>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status404NotFound);

            group.MapGet(
                    "/todos/{id:guid}/pomodoro/runs",
                    async Task<Results<Ok<List<PomodoroRunResponse>>, ProblemHttpResult>> (
                        Guid id,
                        PomodoroService service,
                        HttpContext context,
                        [FromQuery] int page = 1,
                        [FromQuery] int pageSize = 20
                    ) =>
                    {
                        var result = await service.GetRunHistory(id, page, pageSize);
                        return result.Match<Results<Ok<List<PomodoroRunResponse>>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            runs => TypedResults.Ok(runs)
                        );
                    }
                )
                .WithTags("Pomodoro", "Todos")
                .WithName("PomodoroRunHistory")
                .WithSummary("Run history for a todo")
                .WithDescription("Every run of this todo, any status, newest first.")
                .Produces<List<PomodoroRunResponse>>(StatusCodes.Status200OK);

            group.MapGet(
                    "/pomodoro/stats",
                    async Task<Results<Ok<PomodoroStatsResponse>, ProblemHttpResult>> (
                        PomodoroService service,
                        HttpContext context,
                        [FromQuery] DateTimeOffset? from = null,
                        [FromQuery] DateTimeOffset? to = null
                    ) =>
                    {
                        var result = await service.GetStats(from, to);
                        return result.Match<Results<Ok<PomodoroStatsResponse>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            stats => TypedResults.Ok(stats)
                        );
                    }
                )
                .WithTags("Pomodoro")
                .WithName("PomodoroStats")
                .WithSummary("Pomodoro statistics")
                .WithDescription("Completed focus/break minutes and run counts, per day in the user's time zone. Defaults to the last 7 days.")
                .Produces<PomodoroStatsResponse>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest);

            group.MapGet(
                    "/todos/{id:guid}/pomodoro/active-run",
                    async Task<Results<Ok<PomodoroRunResponse>, ProblemHttpResult>> (
                        Guid id,
                        PomodoroService service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.GetActiveRun(id);
                        return result.Match<Results<Ok<PomodoroRunResponse>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            run => TypedResults.Ok(run)
                        );
                    }
                )
                .WithTags("Pomodoro", "Todos")
                .WithName("PomodoroGetActiveRun")
                .WithSummary("Get active Pomodoro run")
                .WithDescription("Returns the active or paused Pomodoro run for a todo.")
                .Produces<PomodoroRunResponse>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status404NotFound);

            group.MapPut(
                    "/pomodoro/runs/{runId:guid}/pause",
                    async Task<Results<Ok<PomodoroRunResponse>, ProblemHttpResult>> (
                        Guid runId,
                        PomodoroService service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.PauseRun(runId);
                        return result.Match<Results<Ok<PomodoroRunResponse>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            run => TypedResults.Ok(run)
                        );
                    }
                )
                .WithTags("Pomodoro")
                .WithName("PomodoroPauseRun")
                .WithSummary("Pause Pomodoro run")
                .WithDescription("Pauses an active Pomodoro run.")
                .Produces<PomodoroRunResponse>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status404NotFound);

            group.MapPut(
                    "/pomodoro/runs/{runId:guid}/resume",
                    async Task<Results<Ok<PomodoroRunResponse>, ProblemHttpResult>> (
                        Guid runId,
                        PomodoroService service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.ResumeRun(runId);
                        return result.Match<Results<Ok<PomodoroRunResponse>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            run => TypedResults.Ok(run)
                        );
                    }
                )
                .WithTags("Pomodoro")
                .WithName("PomodoroResumeRun")
                .WithSummary("Resume Pomodoro run")
                .WithDescription("Resumes a paused Pomodoro run.")
                .Produces<PomodoroRunResponse>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status404NotFound);

            group.MapPut(
                    "/pomodoro/runs/{runId:guid}/advance",
                    async Task<Results<Ok<PomodoroRunResponse>, ProblemHttpResult>> (
                        Guid runId,
                        AdvancePomodoroRun request,
                        PomodoroService service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.AdvanceRun(runId, request);
                        return result.Match<Results<Ok<PomodoroRunResponse>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            run => TypedResults.Ok(run)
                        );
                    }
                )
                .WithTags("Pomodoro")
                .WithName("PomodoroAdvanceRun")
                .WithSummary("Advance Pomodoro run phase")
                .WithDescription("Completes the current phase and advances to the next one.")
                .Produces<PomodoroRunResponse>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status404NotFound);

            group.MapPut(
                    "/pomodoro/runs/{runId:guid}/cancel",
                    async Task<Results<Ok<PomodoroRunResponse>, ProblemHttpResult>> (
                        Guid runId,
                        PomodoroService service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.CancelRun(runId);
                        return result.Match<Results<Ok<PomodoroRunResponse>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            run => TypedResults.Ok(run)
                        );
                    }
                )
                .WithTags("Pomodoro")
                .WithName("PomodoroCancelRun")
                .WithSummary("Cancel Pomodoro run")
                .WithDescription("Cancels an active or paused Pomodoro run.")
                .Produces<PomodoroRunResponse>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status404NotFound);
        }
    }
}
