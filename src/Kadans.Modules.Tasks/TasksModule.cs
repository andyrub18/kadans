using Kadans.Modules.Tasks.Features;
using Kadans.Modules.Tasks.Features.Pomodoro;
using Kadans.Modules.Tasks.Features.Todos;
using Kadans.Modules.Tasks.Features.Todos.Occurrences;
using Kadans.Modules.Tasks.Persistence;
using Kadans.SharedKernel.Modules;
using Kadans.SharedKernel.Persistence;
using Kadans.SharedKernel.Users;
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
        var connectionString = KadansDatabase.ConnectionString(configuration);
        services.AddDbContext<TasksDbContext>(options =>
            options.UseNpgsql(
                connectionString,
                npgsql => npgsql.MigrationsHistoryTable("__ef_migrations_history", TasksDbContext.Schema)
            ),
            optionsLifetime: ServiceLifetime.Singleton // built once: the options depend on configuration only
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

            quartz.AddJob<OccurrenceReminderJob>(job => job.WithIdentity(OccurrenceReminderJob.Key));
            quartz.AddTrigger(trigger =>
                trigger
                    .ForJob(OccurrenceReminderJob.Key)
                    .WithIdentity("occurrence-reminder-trigger", "tasks")
                    .StartAt(DateBuilder.FutureDate(5, IntervalUnit.Second))
                    .WithSimpleSchedule(s => s.WithIntervalInSeconds(Math.Max(5, tasksOptions.ReminderIntervalSeconds)).RepeatForever())
            );

            // Nightly, and once soon after a start so a server that restarts often still cleans up.
            quartz.AddJob<TasksRetentionJob>(job => job.WithIdentity(TasksRetentionJob.Key));
            quartz.AddTrigger(trigger =>
                trigger
                    .ForJob(TasksRetentionJob.Key)
                    .WithIdentity("tasks-retention-nightly", "tasks")
                    .WithCronSchedule(Retention.NightlyCron, cron => cron.InTimeZone(TimeZoneInfo.Utc))
            );
            quartz.AddTrigger(trigger =>
                trigger
                    .ForJob(TasksRetentionJob.Key)
                    .WithIdentity("tasks-retention-startup", "tasks")
                    .StartAt(DateBuilder.FutureDate(2, IntervalUnit.Minute))
            );
        });

        services.AddScoped<TodoCreation>();
        services.AddScoped<TodoErasure>();
        services.AddScoped<IUserDataEraser>(provider => provider.GetRequiredService<TodoErasure>());
        services.AddScoped<TodoUpdate>();
        services.AddScoped<GetTodos>();
        services.AddScoped<PomodoroService>();
        services.AddSingleton<TasksMetrics>();
        services.AddScoped<PomodoroAutoAdvancer>();
        services.AddScoped<PomodoroTimeUp>();
        services.AddScoped<PomodoroAutoFinish>();
        services.AddSingleton<PomodoroDeadlineSignal>();
        services.AddHostedService<PomodoroDeadlineWatcher>();
    }

    public Task InitializeAsync(IServiceProvider services, CancellationToken cancellationToken) =>
        services.MigrateIfConfiguredAsync<TasksDbContext>(cancellationToken);

    public void MapEndpoints(IEndpointRouteBuilder endpoints)
    {
        endpoints.MapTodoRoutes();
        endpoints.MapOccurrenceRoutes();
        endpoints.MapPomodoroRoutes();
    }
}
