using System.Text;
using Kadans.SharedKernel.Errors;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.OpenApi;
using Microsoft.OpenApi;

namespace Kadans.Api.Documentation;

public class GlobalOpenApiDocumentation(IAuthenticationSchemeProvider authenticationSchemeProvider)
    : IOpenApiDocumentTransformer
{
    private static readonly string overview = BuildOverview();

    const string overviewHeader = """
        KADANS API DOCUMENTATION
        ==============================================

        ## Overview
        This API provides endpoints for managing TODO items.
        It allows clients to perform various actions such as creating, updating, deleting, and retrieving TODO items.

        ## Error Handling
        The API uses standard HTTP status codes to indicate the success or failure of requests. In case of errors, the API will
        return a problem details JSON response with an error code and message.

        ### Error Format

        The error response will be in the following format according to the Problem Details specification (Rfc 9457):

        ```json
        {
            "type": "Link to documentation or error type",
            "title": "Short description of the error",
            "status": "Http Status code (int)",
            "detail": "Further details about the error",
            "instance": "Unique identifier for the error instance",
            "errorCode": "A custom extension used by the API to provide additional error information. The error list is provided below."
        }
        ```

        ### Error list
        | Error Code | HTTP Status | Description |
        |------------|-------------|-------------|
        """;

    static string BuildOverview()
    {
        var builder = new StringBuilder();
        builder.AppendLine(overviewHeader);

        foreach (
            var error in ErrorTypes.List.OrderBy(
                static error => error.Value,
                StringComparer.Ordinal
            )
        )
        {
            builder.Append("| ");
            builder.Append(error.Value);
            builder.Append(" | ");
            builder.Append(error.HttpStatusCode);
            builder.Append(" | ");
            builder.Append(error.Name.Replace("|", "\\|", StringComparison.Ordinal));
            builder.AppendLine(" |");
        }

        return builder.ToString();
    }

    public async Task TransformAsync(
        OpenApiDocument document,
        OpenApiDocumentTransformerContext context,
        CancellationToken cancellationToken
    )
    {
        document.Servers =
        [
            new() { Description = "Development server", Url = "http://localhost:5036/" },
        ];

        var authenticationSchemes = await authenticationSchemeProvider.GetAllSchemesAsync();
        if (
            authenticationSchemes.Any(authenticationScheme => authenticationScheme.Name == "Bearer")
        )
        {
            var securityScheme = new OpenApiSecurityScheme
            {
                Type = SecuritySchemeType.Http,
                Scheme = "bearer",
                In = ParameterLocation.Header,
                BearerFormat = "JWT",
            };

            document.Components ??= new OpenApiComponents();
            document.Components.SecuritySchemes ??=
                new Dictionary<string, IOpenApiSecurityScheme>();
            document.Components.SecuritySchemes["Bearer"] = securityScheme;
        }

        // Apply it as a requirement for all operations
        var securityRequirement = new OpenApiSecurityRequirement
        {
            { new OpenApiSecuritySchemeReference("Bearer"), [] },
        };

        foreach (var pathItem in document.Paths.Values)
        {
            if (pathItem.Operations is null)
                continue;
            foreach (var operation in pathItem.Operations.Values)
            {
                operation.Security ??= [];
                operation.Security.Add(securityRequirement);
            }
        }

        document.Info = new()
        {
            Title = "TODO App API",
            Version = "0.0.1-alpha",
            Description = overview,
            Contact = new() { Name = "Anderson Ruban", Email = "andersonruban1281@gmail.com" },
        };
    }
}
