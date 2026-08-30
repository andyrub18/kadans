using System.Text.Json.Serialization;
using Humanizer;
using Kadans.Api.Documentation;
using Kadans.Modules.Identity;
using Kadans.Modules.Tasks;
using Kadans.SharedKernel.BackgroundTasks;
using Kadans.SharedKernel.Modules;
using Kadans.SharedKernel.Security;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.OpenApi;
using Scalar.AspNetCore;
using Serilog;

var builder = WebApplication.CreateBuilder(args);

// Modules own their services, persistence and endpoints; the host only wires them together.
IModule[] modules = [new IdentityModule(), new TasksModule()];

builder.Host.UseSerilog(
    (context, configuration) => configuration.ReadFrom.Configuration(context.Configuration)
);

builder.Services.AddOpenApi(options =>
{
    options.AddDocumentTransformer<GlobalOpenApiDocumentation>();
    options.AddSchemaTransformer(
        (schema, context, _) =>
        {
            switch (context.JsonTypeInfo.Type)
            {
                case var t when t == typeof(ProblemDetails):
                {
                    schema.Description = "A problem response";
                    schema.Properties = new Dictionary<string, IOpenApiSchema>
                    {
                        [nameof(ProblemDetails.Type).Camelize()] = new OpenApiSchema
                        {
                            Type = JsonSchemaType.String,
                            Description = "The rfc standard url for the error type",
                        },
                        [nameof(ProblemDetails.Status).Camelize()] = new OpenApiSchema
                        {
                            Type = JsonSchemaType.Integer,
                            Description =
                                "The http status code tied to this particular type of problem",
                        },
                        [nameof(ProblemDetails.Title).Camelize()] = new OpenApiSchema
                        {
                            Type = JsonSchemaType.String,
                            Description = "The title of the problem",
                        },
                        [nameof(ProblemDetails.Detail).Camelize()] = new OpenApiSchema
                        {
                            Type = JsonSchemaType.String,
                            Description = "The description of the problem",
                        },
                        [nameof(ProblemDetails.Instance).Camelize()] = new OpenApiSchema
                        {
                            Type = JsonSchemaType.String,
                            Description = "The path of the instance of the problem",
                        },
                        ["errorCode"] = new OpenApiSchema
                        {
                            Type = JsonSchemaType.String,
                            Description = "An additional property to identify the error",
                        },
                    };

                    break;
                }
            }

            return Task.CompletedTask;
        }
    );
});

builder.Services.Configure<Microsoft.AspNetCore.Http.Json.JsonOptions>(options =>
    options.SerializerOptions.Converters.Add(new JsonStringEnumConverter())
);

builder.Services.AddHttpContextAccessor();
builder.Services.AddScoped<ICurrentUserService, CurrentUserService>();
builder.Services.AddSingleton<IBackgroundTaskQueue, BackgroundTaskQueue>();
builder.Services.AddHostedService<QueuedHostedService>();

// Every endpoint requires an authenticated user unless it explicitly opts out.
builder
    .Services.AddAuthorizationBuilder()
    .SetFallbackPolicy(new AuthorizationPolicyBuilder().RequireAuthenticatedUser().Build());

foreach (var module in modules)
    module.AddServices(builder.Services, builder.Configuration);

var app = builder.Build();

foreach (var module in modules)
    await module.InitializeAsync(app.Services, app.Lifetime.ApplicationStopping);

if (app.Environment.IsDevelopment())
{
    app.MapOpenApi().AllowAnonymous();
    app.MapScalarApiReference().AllowAnonymous();
}

app.UseAuthentication();
app.UseAuthorization();

app.UseSerilogRequestLogging();
app.UseHttpsRedirection();

foreach (var module in modules)
    module.MapEndpoints(app);

app.Run();
