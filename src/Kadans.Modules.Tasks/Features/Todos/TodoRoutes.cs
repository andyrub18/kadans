using Kadans.Modules.Tasks.Contracts;
using Kadans.SharedKernel.Http;
using Kadans.Modules.Tasks.Features.Todos;
using Kadans.Modules.Tasks.Features.Pomodoro;
using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.AspNetCore.Mvc;
using OneOf.Types;
using TaskStatus = Kadans.Modules.Tasks.Domain.TaskStatus;

namespace Kadans.Modules.Tasks.Features.Todos;

internal static class TodoRoutes
{
    extension(IEndpointRouteBuilder app)
    {
        public void MapTodoRoutes()
        {
            var todos = app.MapGroup("/todos").WithTags("Todos").RequireAuthorization();

            todos
                .MapPost(
                    "/one-time",
                    async Task<Results<Ok<TodoResponse>, ProblemHttpResult>> (
                        CreateOneTimeTodo request,
                        TodoCreation service,
                        HttpContext context
                    ) => (await service.CreateOneTimeTodo(request)).ToHttp(context)
                )
                .WithName("TodosCreateOneTime")
                .WithSummary("Create one-time todo")
                .WithDescription("Creates a one-time todo and materializes its single occurrence.")
                .Produces<TodoResponse>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status500InternalServerError);

            todos
                .MapPost(
                    "/recurring",
                    async Task<Results<Ok<TodoResponse>, ProblemHttpResult>> (
                        CreateRecurringTodo request,
                        TodoCreation service,
                        HttpContext context
                    ) => (await service.CreateRecurringTodo(request)).ToHttp(context)
                )
                .WithName("TodosCreateRecurring")
                .WithSummary("Create recurring todo")
                .WithDescription("Creates a recurring todo and materializes its occurrences up to the horizon.")
                .Produces<TodoResponse>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status500InternalServerError);

            todos
                .MapGet(
                    string.Empty,
                    async Task<Results<Ok<List<TodoResponse>>, ProblemHttpResult>> (
                        GetTodos service,
                        HttpContext context,
                        [FromQuery] int page = 1,
                        [FromQuery] int pageSize = 20,
                        [FromQuery] TaskStatus? status = null
                    ) =>
                    {
                        var result = await service.GetAllTodos(page, pageSize, status);
                        return result.Match<Results<Ok<List<TodoResponse>>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            list => TypedResults.Ok(list)
                        );
                    }
                )
                .WithName("TodosList")
                .WithSummary("List todos")
                .WithDescription("Returns paginated todos, optionally filtered by status.")
                .Produces<List<TodoResponse>>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest);

            todos
                .MapGet(
                    "/{id:guid}",
                    async Task<Results<Ok<TodoResponse>, ProblemHttpResult>> (
                        Guid id,
                        GetTodos service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.GetTodoById(id);
                        return result.Match<Results<Ok<TodoResponse>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            todo => TypedResults.Ok(todo)
                        );
                    }
                )
                .WithName("TodosGetById")
                .WithSummary("Get todo by id")
                .Produces<TodoResponse>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status404NotFound);

            todos
                .MapGet(
                    "/{id:guid}/occurrences",
                    async Task<Results<Ok<List<TodoOccurrenceResponse>>, ProblemHttpResult>> (
                        Guid id,
                        GetTodos service,
                        HttpContext context,
                        [FromQuery] int page = 1,
                        [FromQuery] int pageSize = 20
                    ) =>
                    {
                        var result = await service.GetOccurrencesByTodoId(id, page, pageSize);
                        return result.Match<
                            Results<Ok<List<TodoOccurrenceResponse>>, ProblemHttpResult>
                        >(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            list => TypedResults.Ok(list)
                        );
                    }
                )
                .WithName("TodosListOccurrences")
                .WithSummary("List pending occurrences of a todo")
                .Produces<List<TodoOccurrenceResponse>>(StatusCodes.Status200OK);

            todos
                .MapGet(
                    "/{id:guid}/history",
                    async Task<Results<Ok<List<TodoOccurrenceResponse>>, ProblemHttpResult>> (
                        Guid id,
                        GetTodos service,
                        HttpContext context,
                        [FromQuery] int page = 1,
                        [FromQuery] int pageSize = 20
                    ) =>
                    {
                        var result = await service.GetTodoHistory(id, page, pageSize);
                        return result.Match<
                            Results<Ok<List<TodoOccurrenceResponse>>, ProblemHttpResult>
                        >(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            list => TypedResults.Ok(list)
                        );
                    }
                )
                .WithName("TodosHistory")
                .WithSummary("Todo history")
                .WithDescription(
                    "Returns all occurrences of a todo, including completed and cancelled ones, newest first."
                )
                .Produces<List<TodoOccurrenceResponse>>(StatusCodes.Status200OK);

            todos
                .MapPut(
                    "/{id:guid}",
                    async Task<Results<Ok<TodoResponse>, ProblemHttpResult>> (
                        Guid id,
                        UpdateTodo request,
                        TodoUpdate service,
                        HttpContext context
                    ) => (await service.UpdateTodo(id, request)).ToHttp(context)
                )
                .WithName("TodosUpdate")
                .WithSummary("Update a todo")
                .WithDescription(
                    "Updates details; with a recurrenceRule, untouched future occurrences are regenerated and touched ones kept."
                )
                .Produces<TodoResponse>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status404NotFound);

            todos
                .MapPut(
                    "/{id:guid}/reschedule",
                    async Task<Results<Ok<TodoOccurrenceResponse>, ProblemHttpResult>> (
                        Guid id,
                        RescheduleOccurrence request,
                        TodoUpdate service,
                        HttpContext context
                    ) => (await service.RescheduleNextOccurrence(id, request)).ToHttp(context)
                )
                .WithName("TodosRescheduleNextOccurrence")
                .WithSummary("Reschedule the next pending occurrence")
                .Produces<TodoOccurrenceResponse>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status404NotFound);

            todos
                .MapPut(
                    "/{id:guid}/cancel",
                    async Task<Results<Ok<Success>, ProblemHttpResult>> (
                        Guid id,
                        Cancel request,
                        TodoUpdate service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.CancelTodo(id, request.Reason);
                        return result.Match<Results<Ok<Success>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            _ => TypedResults.Ok(new Success())
                        );
                    }
                )
                .WithName("TodosCancel")
                .WithSummary("Cancel a todo")
                .WithDescription("Cancels the todo and all of its pending occurrences.")
                .Produces<Success>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status404NotFound);

            todos
                .MapPost(
                    "/{id:guid}/remarks",
                    async Task<Results<Ok<Success>, ProblemHttpResult>> (
                        Guid id,
                        AddRemark request,
                        TodoUpdate service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.AddRemark(id, request.Remark);
                        return result.Match<Results<Ok<Success>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            _ => TypedResults.Ok(new Success())
                        );
                    }
                )
                .WithName("TodosAddRemark")
                .WithSummary("Add a remark to a todo")
                .Produces<Success>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status404NotFound);

            todos
                .MapPut(
                    "/{id:guid}/remarks",
                    async Task<Results<Ok<Success>, ProblemHttpResult>> (
                        Guid id,
                        ReplaceRemarks request,
                        TodoUpdate service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.UpdateAllTodosRemarks(id, request.Remarks);
                        return result.Match<Results<Ok<Success>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            _ => TypedResults.Ok(new Success())
                        );
                    }
                )
                .WithName("TodosReplaceRemarks")
                .WithSummary("Replace all remarks of a todo")
                .Produces<Success>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status404NotFound);
        }

        public void MapOccurrenceRoutes()
        {
            var occurrences = app.MapGroup("/occurrences")
                .WithTags("Occurrences")
                .RequireAuthorization();

            occurrences
                .MapGet(
                    string.Empty,
                    async Task<Results<Ok<List<TodoOccurrenceResponse>>, ProblemHttpResult>> (
                        GetTodos service,
                        HttpContext context,
                        [FromQuery(Name = "from")] DateTimeOffset from,
                        [FromQuery(Name = "to")] DateTimeOffset to
                    ) =>
                    {
                        var result = await service.GetOccurrencesByDateRange(from, to);
                        return result.Match<
                            Results<Ok<List<TodoOccurrenceResponse>>, ProblemHttpResult>
                        >(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            list => TypedResults.Ok(list)
                        );
                    }
                )
                .WithName("OccurrencesListByRange")
                .WithSummary("List pending occurrences in a date range")
                .WithDescription("Pending occurrences across all todos between `from` and `to`; beyond the materialization horizon, computed previews (`isPreview`) fill the window.")
                .Produces<List<TodoOccurrenceResponse>>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest);

            occurrences
                .MapPut(
                    "/{id:guid}/reschedule",
                    async Task<Results<Ok<TodoOccurrenceResponse>, ProblemHttpResult>> (
                        Guid id,
                        RescheduleOccurrence request,
                        TodoUpdate service,
                        HttpContext context
                    ) => (await service.RescheduleOccurrence(id, request)).ToHttp(context)
                )
                .WithName("OccurrencesReschedule")
                .WithSummary("Reschedule an occurrence")
                .WithDescription("Moves one pending occurrence without touching the rule; the row keeps its original instant as identity.")
                .Produces<TodoOccurrenceResponse>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status404NotFound);

            occurrences
                .MapPut(
                    "/{id:guid}/complete",
                    async Task<Results<Ok<Success>, ProblemHttpResult>> (
                        Guid id,
                        TodoUpdate service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.CompleteOccurrence(id);
                        return result.Match<Results<Ok<Success>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            _ => TypedResults.Ok(new Success())
                        );
                    }
                )
                .WithName("OccurrencesComplete")
                .WithSummary("Complete an occurrence")
                .Produces<Success>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status404NotFound);

            occurrences
                .MapPut(
                    "/{id:guid}/cancel",
                    async Task<Results<Ok<Success>, ProblemHttpResult>> (
                        Guid id,
                        Cancel request,
                        TodoUpdate service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.CancelOccurrence(id, request.Reason);
                        return result.Match<Results<Ok<Success>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            _ => TypedResults.Ok(new Success())
                        );
                    }
                )
                .WithName("OccurrencesCancel")
                .WithSummary("Cancel an occurrence")
                .Produces<Success>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status404NotFound);

            occurrences
                .MapPut(
                    "/{id:guid}/remark",
                    async Task<Results<Ok<Success>, ProblemHttpResult>> (
                        Guid id,
                        AddRemark request,
                        TodoUpdate service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.AddRemarkToOccurrence(id, request.Remark);
                        return result.Match<Results<Ok<Success>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            _ => TypedResults.Ok(new Success())
                        );
                    }
                )
                .WithName("OccurrencesSetRemark")
                .WithSummary("Set the remark of an occurrence")
                .Produces<Success>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status404NotFound);
        }
    }
}
