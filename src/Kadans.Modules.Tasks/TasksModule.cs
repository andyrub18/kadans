using Kadans.Modules.Tasks.Features.Pomodoro;
using Kadans.Modules.Tasks.Features.Todos;
using Kadans.Modules.Tasks.Features.Todos.Occurrences;
using Kadans.Modules.Tasks.Persistence;
using Kadans.SharedKernel.Modules;
using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Quartz;

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

        var tasksSection = configuration.GetSection(TasksOptions.SectionName);
        services.Configure<TasksOptions>(tasksSection);
        var tasksOptions = tasksSection.Get<TasksOptions>() ?? new TasksOptions();

        services.AddScoped<OccurrenceGenerator>();
        services.AddQuartz(quartz =>
        {
            quartz.AddJob<OccurrenceHorizonJob>(job => job.WithIdentity(OccurrenceHorizonJob.Key));
            quartz.AddTrigger(trigger =>
                trigger
                    .ForJob(OccurrenceHorizonJob.Key)
                    .WithIdentity("occurrence-horizon-trigger", "tasks")
                    .StartAt(DateBuilder.FutureDate(10, IntervalUnit.Second))
                    .WithSimpleSchedule(s => s.WithIntervalInMinutes(Math.Max(1, tasksOptions.HorizonRefreshMinutes)).RepeatForever())
            );

            quartz.AddJob<PomodoroAutoAdvanceJob>(job => job.WithIdentity(PomodoroAutoAdvanceJob.Key));
            quartz.AddTrigger(trigger =>
                trigger
                    .ForJob(PomodoroAutoAdvanceJob.Key)
                    .WithIdentity("pomodoro-auto-advance-trigger", "tasks")
                    .StartAt(DateBuilder.FutureDate(5, IntervalUnit.Second))
                    .WithSimpleSchedule(s => s.WithIntervalInSeconds(Math.Max(5, tasksOptions.PomodoroAutoAdvanceSeconds)).RepeatForever())
            );

            quartz.AddJob<OccurrenceReminderJob>(job => job.WithIdentity(OccurrenceReminderJob.Key));
            quartz.AddTrigger(trigger =>
                trigger
                    .ForJob(OccurrenceReminderJob.Key)
                    .WithIdentity("occurrence-reminder-trigger", "tasks")
                    .StartAt(DateBuilder.FutureDate(5, IntervalUnit.Second))
                    .WithSimpleSchedule(s => s.WithIntervalInSeconds(Math.Max(5, tasksOptions.ReminderIntervalSeconds)).RepeatForever())
            );
        });

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
