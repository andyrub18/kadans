using Kadans.SharedKernel.Errors;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Http.HttpResults;
using OneOf;
using OneOf.Types;

namespace Kadans.SharedKernel.Http;

/// <summary>Maps service results to HTTP: the value as 200, an <see cref="ApplicationError"/> as ProblemDetails.</summary>
public static class ResultExtensions
{
    public static Results<Ok<T>, ProblemHttpResult> ToHttp<T>(
        this OneOf<ApplicationError, T> result,
        HttpContext context
    ) =>
        result.Match<Results<Ok<T>, ProblemHttpResult>>(
            error => TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
            value => TypedResults.Ok(value)
        );

    /// <summary>For operations whose only outcome is "done": a <c>Success</c> body on 200.</summary>
    public static Results<Ok<Success>, ProblemHttpResult> ToHttpSuccess(
        this OneOf<ApplicationError, Success> result,
        HttpContext context
    ) => result.ToHttp(context);
}
