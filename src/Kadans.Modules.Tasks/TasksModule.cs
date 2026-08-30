using Kadans.Modules.Tasks.Features.Pomodoro;
using Kadans.Modules.Tasks.Features.Todos;
using Kadans.Modules.Tasks.Features.Todos.Occurrences;
using Kadans.Modules.Tasks.Persistence;
using Kadans.SharedKernel.Modules;
using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;

namespace Kadans.Modules.Tasks;

/// <summary>Todos, occurrences and Pomodoro sessions. Owns the <c>tasks</c> schema.</summary>
public sealed class TasksModule : IModule
{
    public void AddServices(IServiceCollection services, IConfiguration configuration)
    {
        services.AddDbContext<TasksDbContext>(options =>
            options.UseNpgsql(
                configuration.GetConnectionString("kadans"),
                npgsql => npgsql.MigrationsHistoryTable("__ef_migrations_history", TasksDbContext.Schema)
            )
        );

        services.Configure<TasksOptions>(configuration.GetSection(TasksOptions.SectionName));
        services.AddScoped<OccurrenceGenerator>();
        services.AddHostedService<OccurrenceHorizonJob>();

        services.AddScoped<TodoCreation>();
        services.AddScoped<TodoUpdate>();
        services.AddScoped<GetTodos>();
        services.AddScoped<PomodoroService>();
    }

    public void MapEndpoints(IEndpointRouteBuilder endpoints)
    {
        endpoints.MapTodoRoutes();
        endpoints.MapOccurrenceRoutes();
        endpoints.MapPomodoroRoutes();
    }
}
