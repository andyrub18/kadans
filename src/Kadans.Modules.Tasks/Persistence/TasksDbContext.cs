using Kadans.Modules.Tasks.Domain;
using Kadans.SharedKernel.Persistence;
using Kadans.SharedKernel.Security;
using Microsoft.EntityFrameworkCore;
using TaskStatus = Kadans.Modules.Tasks.Domain.TaskStatus;

namespace Kadans.Modules.Tasks.Persistence;

internal sealed class TasksDbContext(
    DbContextOptions<TasksDbContext> options,
    ICurrentUserService userService
) : DbContext(options)
{
    public const string Schema = "tasks";

    public const string ACTIVE_OCCURRENCES_FILTER = "ActiveOccurrences";
    public const string USER_FILTER = "UserFilter";
    public const string ACTIVE_TODOS_FILTER = "ActiveTodos";

    public DbSet<Todo> Todos => Set<Todo>();
    public DbSet<RecurrenceRule> RecurrenceRules => Set<RecurrenceRule>();
    public DbSet<TodoOccurrence> TodoOccurrences => Set<TodoOccurrence>();
    public DbSet<PomodoroTemplate> PomodoroTemplates => Set<PomodoroTemplate>();
    public DbSet<PomodoroTemplatePhase> PomodoroTemplatePhases => Set<PomodoroTemplatePhase>();
    public DbSet<PomodoroRun> PomodoroRuns => Set<PomodoroRun>();
    public DbSet<PomodoroRunPhase> PomodoroRunPhases => Set<PomodoroRunPhase>();

    protected override void ConfigureConventions(ModelConfigurationBuilder configurationBuilder)
    {
        base.ConfigureConventions(configurationBuilder);
        configurationBuilder.StoreDateTimeOffsetsAsUtc();
    }

    protected override void OnModelCreating(ModelBuilder builder)
    {
        base.OnModelCreating(builder);

        builder.HasDefaultSchema(Schema);
        builder.UseSnakeCaseNames();

        builder.Entity<Todo>(t =>
        {
            t.OwnsMany(
                r => r.Remarks,
                a =>
                {
                    a.WithOwner().HasForeignKey("TodoId");
                    a.Property<Guid>("Id").ValueGeneratedOnAdd();
                    a.HasKey("Id");
                    a.Property(r => r.Remark).IsRequired().HasMaxLength(2000);
                    a.ToTable("todo_remarks");
                }
            );

            t.Property(p => p.Description).IsRequired().HasMaxLength(4000);
            t.Property(p => p.Title).IsRequired().HasMaxLength(500);
            t.Property(p => p.Status).HasConversion<string>();

            // Users live in the Identity module: reference by id only, no FK/navigation.
            t.Property(todo => todo.UserId).IsRequired().HasMaxLength(450);

            t.HasOne(todo => todo.PomodoroTemplate)
                .WithMany()
                .HasForeignKey(todo => todo.PomodoroTemplateId)
                .OnDelete(DeleteBehavior.SetNull);

            t.HasIndex(todo => new { todo.UserId, todo.CreatedAt })
                .HasDatabaseName("ix_todos_user_id_created_at_desc")
                .IsDescending(false, true);

            t.HasIndex(todo => new
                {
                    todo.UserId,
                    todo.Status,
                    todo.CreatedAt,
                })
                .HasDatabaseName("ix_todos_user_id_status_created_at_desc")
                .IsDescending(false, false, true);

            t.HasIndex(todo => new
                {
                    todo.UserId,
                    todo.CreatedAt,
                    todo.Id,
                })
                .HasDatabaseName("ix_todos_user_id_created_at_id_active")
                .IsDescending(false, true, false)
                .HasFilter("status IN ('Scheduled', 'Started')");

            t.HasIndex(todo => new { todo.UserId, todo.Id }).HasDatabaseName("ix_todos_user_id_id");

            // What the horizon job scans: active todos that are not generated far enough ahead.
            t.HasIndex(todo => todo.OccurrencesGeneratedThrough)
                .HasDatabaseName("ix_todos_generated_through_active")
                .HasFilter("status IN ('Scheduled', 'Started')");

            t.HasQueryFilter(
                ACTIVE_TODOS_FILTER,
                todo => todo.Status != TaskStatus.Completed && todo.Status != TaskStatus.Cancelled
            );
            t.HasQueryFilter(USER_FILTER, todo => todo.UserId == userService.UserId);
        });

        builder.Entity<RecurrenceRule>(r =>
        {
            r.HasKey(p => p.Id);
            r.Property(p => p.Rrule).IsRequired().HasMaxLength(512);
            r.Property(p => p.TimeZoneId).IsRequired().HasMaxLength(64);
        });

        builder.Entity<TodoOccurrence>(t =>
        {
            t.Property(p => p.Status).HasConversion<string>();
            t.Property(p => p.Remarks).HasMaxLength(4000);
            t.Property(p => p.CancellationReason).HasMaxLength(4000);
            t.Property(p => p.RescheduleReason).HasMaxLength(4000);

            t.HasOne(o => o.Todo)
                .WithMany()
                .HasForeignKey(o => o.TodoId)
                .OnDelete(DeleteBehavior.Cascade);

            // The rule instance is the identity: regeneration is idempotent on it.
            t.HasIndex(o => new { o.TodoId, o.OriginalScheduledAt })
                .IsUnique()
                .HasDatabaseName("ix_todo_occurrences_todo_id_original_scheduled_at");

            t.HasIndex(o => o.ScheduledAt)
                .HasDatabaseName("ix_todo_occurrences_scheduled_at_pending")
                .HasFilter("status = 'Pending'");

            // What the reminder job scans.
            t.HasIndex(o => o.NotifyAt)
                .HasDatabaseName("ix_todo_occurrences_notify_due")
                .HasFilter("status = 'Pending' AND notified_at IS NULL AND notify_at IS NOT NULL");

            t.HasQueryFilter(ACTIVE_OCCURRENCES_FILTER, o => o.Status == OccurrenceStatus.Pending);
            t.HasQueryFilter(
                USER_FILTER,
                o => o.Todo != null && o.Todo.UserId == userService.UserId
            );
        });

        builder.Entity<PomodoroTemplate>(t =>
        {
            t.HasKey(p => p.Id);
            t.Property(p => p.Name).IsRequired().HasMaxLength(200);
            t.Property(p => p.UserId).IsRequired().HasMaxLength(450);

            t.HasMany(p => p.Phases)
                .WithOne()
                .HasForeignKey(p => p.PomodoroTemplateId)
                .OnDelete(DeleteBehavior.Cascade);

            t.HasIndex(p => new { p.UserId, p.CreatedAt })
                .HasDatabaseName("ix_pomodoro_templates_user_id_created_at_desc")
                .IsDescending(false, true);

            t.HasQueryFilter(p => p.UserId == userService.UserId);
        });

        builder.Entity<PomodoroTemplatePhase>(p =>
        {
            p.HasKey(x => x.Id);
            p.Property(x => x.Type).HasConversion<string>();

            p.HasIndex(x => new { x.PomodoroTemplateId, x.Order })
                .HasDatabaseName("ix_pomodoro_template_phases_template_order");
        });

        builder.Entity<PomodoroRun>(r =>
        {
            r.HasKey(x => x.Id);
            r.Property(x => x.Status).HasConversion<string>();
            r.Property(x => x.UserId).IsRequired().HasMaxLength(450);

            r.HasOne(x => x.Todo)
                .WithMany()
                .HasForeignKey(x => x.TodoId)
                .OnDelete(DeleteBehavior.Cascade);

            r.HasMany(x => x.Phases)
                .WithOne()
                .HasForeignKey(x => x.PomodoroRunId)
                .OnDelete(DeleteBehavior.Cascade);

            r.HasIndex(x => new { x.TodoId, x.StartedAt })
                .HasDatabaseName("ix_pomodoro_runs_todo_id_started_at_desc")
                .IsDescending(false, true);

            r.HasIndex(x => new
                {
                    x.UserId,
                    x.Status,
                    x.StartedAt,
                })
                .HasDatabaseName("ix_pomodoro_runs_user_id_status_started_at_desc")
                .IsDescending(false, false, true);

            r.HasQueryFilter(x => x.UserId == userService.UserId);
        });

        builder.Entity<PomodoroRunPhase>(p =>
        {
            p.HasKey(x => x.Id);
            p.Property(x => x.Type).HasConversion<string>();

            p.HasIndex(x => new { x.PomodoroRunId, x.Order })
                .HasDatabaseName("ix_pomodoro_run_phases_run_order");
        });
    }
}
