using Kadans.SharedKernel.Localization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;

namespace Kadans.SharedKernel.Errors;

/// <summary>
/// Services describe a failure once, in English, next to the code that detected it. The wording a
/// user reads is chosen at the HTTP boundary: <see cref="ToProblemDetails(HttpContext)"/> translates
/// <c>detail</c> (and validation messages) into the request's language through <see cref="ErrorTexts"/>.
/// <c>errorCode</c> and each validation entry's <c>code</c> never change with the language — they are the contract.
/// </summary>
public record ApplicationError(ErrorTypes ErrorType, string ErrorMessage)
{
    /// <summary>The response for this request, in the language it asked for.</summary>
    public ProblemDetails ToProblemDetails(HttpContext context) =>
        ToProblemDetails(context.Request.Path, RequestLanguage.Of(context));

    public virtual ProblemDetails ToProblemDetails(string instance, string language = RequestLanguage.Default) =>
        new()
        {
            Title = ErrorType.Name,
            Detail = ErrorTexts.Localize(ErrorType, ErrorMessage, language),
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
    public override ProblemDetails ToProblemDetails(string instance, string language = RequestLanguage.Default) =>
        new()
        {
            Title = ErrorType.Name,
            Detail = ErrorTexts.Localize(ErrorType, ErrorMessage, language),
            Instance = instance,
            Type = ErrorType.RfcType,
            Status = ErrorType.HttpStatusCode,
            Extensions =
            {
                ["errorCode"] = ErrorType.Value,
                ["errors"] = Errors.ConvertAll(e => new { e.Code, Message = ErrorTexts.LocalizeEntry(e.Code, e.Message, language) }),
            },
        };
}
