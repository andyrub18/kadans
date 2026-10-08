using System.Text.Json.Serialization;
using Humanizer;
using Kadans.Api;
using Kadans.Api.Documentation;
using Kadans.Modules.Billing;
using Kadans.Modules.Budget;
using Kadans.Modules.Identity;
using Kadans.Modules.Notifications;
using Kadans.Modules.Tasks;
using Kadans.SharedKernel.Email;
using Kadans.SharedKernel.Localization;
using Kadans.SharedKernel.Modules;
using Kadans.SharedKernel.Security;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.OpenApi;
using Quartz;
using Scalar.AspNetCore;
using Serilog;

var builder = WebApplication.CreateBuilder(args);

// Outside Development a missing secret must stop the process with a sentence, not surface later as a
// NullReferenceException in the JWT handler or as tokens nobody can validate.
if (!builder.Environment.IsDevelopment())
    ProductionConfiguration.ThrowIfIncomplete(builder.Configuration);

// Modules own their services, persistence and endpoints; the host only wires them together.
IModule[] modules = [new IdentityModule(), new TasksModule(), new NotificationsModule(), new BudgetModule(), new BillingModule()];

// Console always (docker compose logs); Loki too when Telemetry:LogsEndpoint is set.
builder.Host.UseSerilog(
    (context, configuration) =>
        configuration
            .ReadFrom.Configuration(context.Configuration)
            .WriteToLoki(Telemetry.Options(context.Configuration), context.HostingEnvironment.EnvironmentName)
);

// Metrics to Prometheus when Telemetry:MetricsEndpoint is set (ARCHITECTURE → Observability).
builder.AddKadansMetrics();

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

// /health/live: the process answers. /health/ready: it can also reach Postgres (what a proxy or an
// uptime monitor should watch). Both anonymous, neither says anything about the data.
builder.Services.AddHealthChecks().AddCheck<PostgresHealthCheck>("postgres", tags: ["ready"]);

builder.Services.AddHttpContextAccessor();
builder.Services.AddScoped<ICurrentUserService, CurrentUserService>();
builder.Services.AddScoped<IRequestLanguage, HttpRequestLanguage>(); // Accept-Language → en | fr | ht
builder.Services.AddKadansEmail(builder.Configuration);

// Quartz runs the modules' scheduled jobs (each module adds its own via AddQuartz, which is additive).
builder.Services.AddQuartz();
builder.Services.AddQuartzHostedService(options => options.WaitForJobsToComplete = true);

// Per-client limits: a global one, plus the email and credentials policies modules put on their endpoints.
builder.Services.AddKadansRateLimiting(builder.Configuration);

// The server's own limit: past what it can work on at once, a request waits briefly for a place or is answered 503.
builder.Services.AddKadansAdmissionControl(builder.Configuration);

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

// Before authentication: a flood, or more than the server can take, is turned away before any token or password work.
app.UseRateLimiter();
app.UseKadansAdmissionControl();
app.UseAuthentication();
app.UseAuthorization();

app.UseSerilogRequestLogging(options => options.GetLevel = Telemetry.RequestLogLevel);
app.UseHttpsRedirection();

foreach (var module in modules)
    module.MapEndpoints(app);

app.MapHealthChecks("/health/live", new() { Predicate = _ => false }).AllowAnonymous();
app.MapHealthChecks("/health/ready", new() { Predicate = check => check.Tags.Contains("ready") }).AllowAnonymous();

app.Run();
