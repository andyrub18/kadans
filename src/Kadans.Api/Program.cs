using Kadans.SharedKernel.Security;
using System.Text;
using System.Text.Json.Serialization;
using Kadans.Api.BackgroundTasks;
using Kadans.Api.Data;
using Kadans.Api.Documentation;
using Kadans.Api.Routes;
using Kadans.Api.Security;
using Kadans.Api.Services;
using Humanizer;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.Http.Json;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Microsoft.IdentityModel.Tokens;
using Microsoft.OpenApi;
using Scalar.AspNetCore;
using Serilog;

var builder = WebApplication.CreateBuilder(args);

var lockoutConfiguration = builder.Configuration.GetSection("Identity:Lockout");
var maxFailedAccessAttempts = lockoutConfiguration.GetValue<int?>("MaxFailedAccessAttempts") ?? 5;
var defaultLockoutMinutes = lockoutConfiguration.GetValue<int?>("DefaultLockoutMinutes") ?? 15;
var allowedForNewUsers = lockoutConfiguration.GetValue<bool?>("AllowedForNewUsers") ?? true;

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
builder.Services.AddSingleton<IBackgroundTaskQueue, BackgroundTaskQueue>();
builder.Services.AddHostedService<QueuedHostedService>();
builder.Services.AddDbContext<ApplicationDbContext>(options =>
{
    var connectionString = builder.Configuration.GetConnectionString("kadans");
    options.UseNpgsql(connectionString);
});
builder.Services.AddHttpContextAccessor();
builder.Services.AddScoped<ICurrentUserService, CurrentUserService>();
builder.Services.AddScoped<JwtProvider>();
builder.Services.AddScoped<Authentication>();
builder.Services.AddScoped<UserManagement>();
builder.Services.AddScoped<TodoCreation>();
builder.Services.AddScoped<TodoUpdate>();
builder.Services.AddScoped<GetTodos>();
builder.Services.AddScoped<PomodoroService>();
builder
    .Services.AddIdentity<IdentityUser, IdentityRole>(options =>
    {
        options.Lockout.MaxFailedAccessAttempts = maxFailedAccessAttempts;
        options.Lockout.DefaultLockoutTimeSpan = TimeSpan.FromMinutes(defaultLockoutMinutes);
        options.Lockout.AllowedForNewUsers = allowedForNewUsers;
    })
    .AddEntityFrameworkStores<ApplicationDbContext>()
    .AddDefaultTokenProviders();

builder
    .Services.AddAuthentication(options =>
    {
        options.DefaultAuthenticateScheme = JwtBearerDefaults.AuthenticationScheme;
        options.DefaultChallengeScheme = JwtBearerDefaults.AuthenticationScheme;
    })
    .AddJwtBearer(options =>
    {
        options.TokenValidationParameters = new()
        {
            ValidateIssuer = true,
            ValidateAudience = true,
            ValidateLifetime = true,
            ValidateIssuerSigningKey = true,
            ValidIssuer = builder.Configuration["Jwt:Issuer"],
            ValidAudience = builder.Configuration["Jwt:Audience"],
            IssuerSigningKey = new SymmetricSecurityKey(
                Encoding.UTF8.GetBytes(builder.Configuration["Jwt:Key"]!)
            ),
        };
    });

builder.Services.AddAuthorization();

builder.Services.ConfigureOptions<JwtParameterOptionsSetup>();

builder.Host.UseSerilog(
    (context, configuration) => configuration.ReadFrom.Configuration(context.Configuration)
);

var app = builder.Build();

await app.SeedInitialAdminAsync();

if (app.Environment.IsDevelopment())
{
    app.MapOpenApi();
    app.MapScalarApiReference();
}

app.UseAuthentication();
app.UseAuthorization();

app.UseSerilogRequestLogging();
app.UseHttpsRedirection();

app.MapAuthRoutes();
app.MapUserRoutes();
app.MapCreateTodoRoutes();
app.MapGetTodoRoutes();
app.MapUpdateTodoRoutes();
app.MapPomodoroRoutes();

app.Run();
