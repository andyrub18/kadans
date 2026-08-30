using Microsoft.AspNetCore.Mvc;

namespace Kadans.SharedKernel.Errors;

public record ApplicationError(ErrorTypes ErrorType, string ErrorMessage)
{
    public virtual ProblemDetails ToProblemDetails(string instance) =>
        new()
        {
            Title = ErrorType.Name,
            Detail = ErrorMessage,
            Instance = instance,
            Type = ErrorType.RfcType,
            Status = ErrorType.HttpStatusCode,
            Extensions = { ["errorCode"] = ErrorType.Value },
        };
}

public sealed record ValidationError(
    ErrorTypes ErrorType,
    string ErrorMessage,
    List<(string Code, string Message)> Errors
) : ApplicationError(ErrorType, ErrorMessage)
{
    public override ProblemDetails ToProblemDetails(string instance) =>
        new()
        {
            Title = ErrorType.Name,
            Detail = ErrorMessage,
            Instance = instance,
            Type = ErrorType.RfcType,
            Status = ErrorType.HttpStatusCode,
            Extensions =
            {
                ["errorCode"] = ErrorType.Value,
                ["errors"] = Errors.ConvertAll(e => new { e.Code, e.Message }),
            },
        };
}
