using Kadans.Api.DTOs;
using Kadans.SharedKernel.Errors;
using Kadans.Api.Models;
using Kadans.Api.Services;
using Microsoft.AspNetCore.Http.HttpResults;
using OneOf.Types;

namespace Kadans.Api.Routes;

public static class PomodoroRoutes
{
    extension(IEndpointRouteBuilder app)
    {
        public void MapPomodoroRoutes()
        {
            app.MapPost(
                    "/pomodoro/templates",
                    async Task<Results<Ok<PomodoroTemplate>, ProblemHttpResult>> (
                        CreatePomodoroTemplate request,
                        PomodoroService service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.CreateTemplate(request);
                        return result.Match<Results<Ok<PomodoroTemplate>, ProblemHttpResult>>(
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
                .Produces<PomodoroTemplate>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status401Unauthorized)
                .ProducesProblem(StatusCodes.Status500InternalServerError);

            app.MapGet(
                    "/pomodoro/templates",
                    async Task<Results<Ok<List<PomodoroTemplate>>, ProblemHttpResult>> (
                        PomodoroService service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.GetTemplates();
                        return result.Match<Results<Ok<List<PomodoroTemplate>>, ProblemHttpResult>>(
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
                .Produces<List<PomodoroTemplate>>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status500InternalServerError);

            app.MapPut(
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

            app.MapPost(
                    "/todos/{id:guid}/pomodoro/start",
                    async Task<Results<Ok<PomodoroRun>, ProblemHttpResult>> (
                        Guid id,
                        PomodoroService service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.StartRun(id);
                        return result.Match<Results<Ok<PomodoroRun>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            run => TypedResults.Ok(run)
                        );
                    }
                )
                .WithTags("Pomodoro", "Todos")
                .WithName("PomodoroStartRun")
                .WithSummary("Start Pomodoro run")
                .WithDescription("Starts a Pomodoro run for a todo from its attached template.")
                .Produces<PomodoroRun>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status404NotFound);

            app.MapGet(
                    "/todos/{id:guid}/pomodoro/active-run",
                    async Task<Results<Ok<PomodoroRun>, ProblemHttpResult>> (
                        Guid id,
                        PomodoroService service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.GetActiveRun(id);
                        return result.Match<Results<Ok<PomodoroRun>, ProblemHttpResult>>(
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
                .Produces<PomodoroRun>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status404NotFound);

            app.MapPut(
                    "/pomodoro/runs/{runId:guid}/pause",
                    async Task<Results<Ok<PomodoroRun>, ProblemHttpResult>> (
                        Guid runId,
                        PomodoroService service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.PauseRun(runId);
                        return result.Match<Results<Ok<PomodoroRun>, ProblemHttpResult>>(
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
                .Produces<PomodoroRun>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status404NotFound);

            app.MapPut(
                    "/pomodoro/runs/{runId:guid}/resume",
                    async Task<Results<Ok<PomodoroRun>, ProblemHttpResult>> (
                        Guid runId,
                        PomodoroService service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.ResumeRun(runId);
                        return result.Match<Results<Ok<PomodoroRun>, ProblemHttpResult>>(
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
                .Produces<PomodoroRun>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status404NotFound);

            app.MapPut(
                    "/pomodoro/runs/{runId:guid}/advance",
                    async Task<Results<Ok<PomodoroRun>, ProblemHttpResult>> (
                        Guid runId,
                        AdvancePomodoroRun request,
                        PomodoroService service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.AdvanceRun(runId, request);
                        return result.Match<Results<Ok<PomodoroRun>, ProblemHttpResult>>(
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
                .Produces<PomodoroRun>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status404NotFound);

            app.MapPut(
                    "/pomodoro/runs/{runId:guid}/cancel",
                    async Task<Results<Ok<PomodoroRun>, ProblemHttpResult>> (
                        Guid runId,
                        PomodoroService service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.CancelRun(runId);
                        return result.Match<Results<Ok<PomodoroRun>, ProblemHttpResult>>(
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
                .Produces<PomodoroRun>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status404NotFound);
        }
    }
}
