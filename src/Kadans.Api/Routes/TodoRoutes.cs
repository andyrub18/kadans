using Kadans.Api.DTOs;
using Kadans.Api.Models;
using Kadans.Api.Services;
using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.AspNetCore.Mvc;
using OneOf.Types;
using TaskStatus = Kadans.Api.Models.TaskStatus;

namespace Kadans.Api.Routes;

public static class TodoRoutes
{
    extension(IEndpointRouteBuilder app)
    {
        public void MapCreateTodoRoutes()
        {
            app.MapPost(
                    "/todos/one-time",
                    async Task<Results<Ok<Success>, ProblemHttpResult>> (
                        CreateOneTimeTodo request,
                        TodoCreation service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.CreateOneTimeTodo(request);
                        return result.Match<Results<Ok<Success>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            _ => TypedResults.Ok(new Success())
                        );
                    }
                )
                .RequireAuthorization()
                .WithTags("Todos")
                .WithName("TodosCreateOneTime")
                .WithSummary("Create one-time todo")
                .WithDescription("Creates a one-time todo and schedules its initial occurrence.")
                .Produces<Success>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status500InternalServerError);

            app.MapPost(
                    "/todos/recurring",
                    async Task<Results<Ok<Success>, ProblemHttpResult>> (
                        CreateRecurringTodo request,
                        TodoCreation service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.CreateRecurringTodo(request);
                        return result.Match<Results<Ok<Success>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            _ => TypedResults.Ok(new Success())
                        );
                    }
                )
                .RequireAuthorization()
                .WithTags("Todos")
                .WithName("TodosCreateRecurring")
                .WithSummary("Create recurring todo")
                .WithDescription("Creates a recurring todo and generates initial occurrences.")
                .Produces<Success>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status500InternalServerError);
        }

        public void MapGetTodoRoutes()
        {
            app.MapGet(
                    "/todos",
                    async Task<Results<Ok<List<Todo>>, ProblemHttpResult>> (
                        GetTodos service,
                        HttpContext context,
                        [FromQuery] int page = 1,
                        [FromQuery] int pageSize = 20,
                        [FromQuery] TaskStatus? status = null
                    ) =>
                    {
                        var result = await service.GetAllTodos(page, pageSize, status);
                        return result.Match<Results<Ok<List<Todo>>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            todos => TypedResults.Ok(todos)
                        );
                    }
                )
                .RequireAuthorization()
                .WithTags("Todos")
                .WithName("TodosList")
                .WithSummary("List todos")
                .WithDescription("Returns paginated todos, optionally filtered by status.")
                .Produces<List<Todo>>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status404NotFound);

            app.MapGet(
                    "/todos/{id:guid}",
                    async Task<Results<Ok<Todo>, ProblemHttpResult>> (
                        Guid id,
                        GetTodos service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.GetTodoById(id);
                        return result.Match<Results<Ok<Todo>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            todo => TypedResults.Ok(todo)
                        );
                    }
                )
                .RequireAuthorization()
                .WithTags("Todos")
                .WithName("TodosGetById")
                .WithSummary("Get todo by id")
                .WithDescription("Returns a single todo by its identifier.")
                .Produces<Todo>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status404NotFound);

            app.MapGet(
                    "/todos/{id:guid}/occurrences",
                    async Task<Results<Ok<List<TodoOccurrence>>, ProblemHttpResult>> (
                        Guid id,
                        GetTodos service,
                        HttpContext context,
                        [FromQuery] int page = 1,
                        [FromQuery] int pageSize = 20
                    ) =>
                    {
                        var result = await service.GetOccurrencesByTodoId(id, page, pageSize);
                        return result.Match<Results<Ok<List<TodoOccurrence>>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            occurrences => TypedResults.Ok(occurrences)
                        );
                    }
                )
                .RequireAuthorization()
                .WithTags("Todos")
                .WithName("TodosGetOccurrencesByTodo")
                .WithSummary("List occurrences for a todo")
                .WithDescription("Returns paginated occurrences for a specific todo.")
                .Produces<List<TodoOccurrence>>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status404NotFound);

            app.MapGet(
                    "/todos/occurrences",
                    async Task<Results<Ok<List<TodoOccurrence>>, ProblemHttpResult>> (
                        GetTodos service,
                        HttpContext context,
                        [FromQuery] DateTimeOffset startDate,
                        [FromQuery] DateTimeOffset endDate
                    ) =>
                    {
                        var result = await service.GetOccurrencesByDateRange(startDate, endDate);
                        return result.Match<Results<Ok<List<TodoOccurrence>>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            occurrences => TypedResults.Ok(occurrences)
                        );
                    }
                )
                .RequireAuthorization()
                .WithTags("Todos")
                .WithName("TodosGetOccurrencesByRange")
                .WithSummary("List occurrences by date range")
                .WithDescription("Returns occurrences between the provided start and end dates.")
                .Produces<List<TodoOccurrence>>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest);

            app.MapGet(
                    "/todo/history",
                    async Task<Results<Ok<List<TodoOccurrence>>, ProblemHttpResult>> (
                        GetTodos service,
                        HttpContext context,
                        Guid todoId,
                        [FromQuery] int page = 1,
                        [FromQuery] int pageSize = 20
                    ) =>
                    {
                        var result = await service.GetTodoHistory(todoId, page, pageSize);
                        return result.Match<Results<Ok<List<TodoOccurrence>>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            occurrences => TypedResults.Ok(occurrences)
                        );
                    }
                )
                .RequireAuthorization()
                .WithTags("Todos")
                .WithName("TodosGetHistory")
                .WithSummary("Get todo history")
                .WithDescription(
                    "Returns historical occurrences for a todo, including completed or canceled items."
                )
                .Produces<List<TodoOccurrence>>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status404NotFound);
        }

        public void MapUpdateTodoRoutes()
        {
            app.MapPut(
                    "/todos/{id:guid}",
                    async Task<Results<Ok<Success>, ProblemHttpResult>> (
                        Guid id,
                        UpdateTodo request,
                        TodoUpdate service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.UpdateTodo(id, request);
                        return result.Match<Results<Ok<Success>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            _ => TypedResults.Ok(new Success())
                        );
                    }
                )
                .RequireAuthorization()
                .WithTags("Todos")
                .WithName("TodosUpdate")
                .WithSummary("Update a todo")
                .WithDescription("Updates a todo's core information.")
                .Produces<Success>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status404NotFound);

            app.MapPut(
                    "/todos/{id:guid}/reschedule",
                    async Task<Results<Ok<Success>, ProblemHttpResult>> (
                        Guid id,
                        TodoUpdate service,
                        RescheduleNextOccurrence request,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.RescheduleNextOccurrence(id, request);
                        return result.Match<Results<Ok<Success>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            _ => TypedResults.Ok(new Success())
                        );
                    }
                )
                .RequireAuthorization()
                .WithTags("Todos")
                .WithName("TodosRescheduleNextOccurrence")
                .WithSummary("Reschedule next occurrence")
                .WithDescription("Reschedules the next active occurrence for a todo.")
                .Produces<Success>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status404NotFound);

            app.MapPut(
                    "/todos/{id:guid}/cancel",
                    async Task<Results<Ok<Success>, ProblemHttpResult>> (
                        Guid id,
                        TodoUpdate service,
                        Cancel request,
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
                .RequireAuthorization()
                .WithTags("Todos")
                .WithName("TodosCancelOccurrence")
                .WithSummary("Cancel occurrence")
                .WithDescription("Cancels an occurrence of a todo with a reason.")
                .Produces<Success>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status404NotFound);

            app.MapPut(
                    "/todos/{id:guid}/remarks",
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
                .RequireAuthorization()
                .WithTags("Todos")
                .WithName("TodosAddRemark")
                .WithSummary("Add todo remark")
                .WithDescription("Adds a remark to a todo.")
                .Produces<Success>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status404NotFound);

            app.MapPut(
                    "/todos/cancel/{id:guid}",
                    async Task<Results<Ok<Success>, ProblemHttpResult>> (
                        Guid id,
                        TodoUpdate service,
                        Cancel request,
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
                .RequireAuthorization()
                .WithTags("Todos")
                .WithName("TodosCancel")
                .WithSummary("Cancel todo")
                .WithDescription("Cancels a todo and future occurrences with a reason.")
                .Produces<Success>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status404NotFound);

            app.MapPut(
                    "/todos/{id:guid}/complete",
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
                .RequireAuthorization()
                .WithTags("Todos")
                .WithName("TodosCompleteOccurrence")
                .WithSummary("Complete occurrence")
                .WithDescription("Marks an occurrence as completed.")
                .Produces<Success>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status404NotFound);

            app.MapPut(
                    "/todos/{id:guid}/remark",
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
                .RequireAuthorization()
                .WithTags("Todos")
                .WithName("TodosAddOccurrenceRemark")
                .WithSummary("Add occurrence remark")
                .WithDescription("Adds a remark to a specific todo occurrence.")
                .Produces<Success>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status404NotFound);

            app.MapPut(
                    "/todos/{id:guid}/update-remarks",
                    async Task<Results<Ok<Success>, ProblemHttpResult>> (
                        Guid id,
                        List<TodoRemark> request,
                        TodoUpdate service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.UpdateAllTodosRemarks(id, request);
                        return result.Match<Results<Ok<Success>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            _ => TypedResults.Ok(new Success())
                        );
                    }
                )
                .RequireAuthorization()
                .WithTags("Todos")
                .WithName("TodosUpdateAllRemarks")
                .WithSummary("Update all remarks")
                .WithDescription("Replaces all todo remarks with the provided list.")
                .Produces<Success>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status404NotFound);
        }
    }
}
