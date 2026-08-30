using Kadans.SharedKernel.Persistence;
using Kadans.SharedKernel.Security;
using Kadans.Api.Models;
using Kadans.Api.Security;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Identity.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore;
using TaskStatus = Kadans.Api.Models.TaskStatus;

namespace Kadans.Api.Data;

public class ApplicationDbContext(
    DbContextOptions<ApplicationDbContext> options,
    ICurrentUserService userService
) : IdentityDbContext<IdentityUser>(options)
{
    public const string ACTIVE_OCCURRENCES_FILTER = "ActiveOccurrences";
    public const string USER_FILTER = "UserFilter";
    public const string ACTIVE_TODOS_FILTER = "ActiveTodos";

    public DbSet<Todo> Todos { get; set; }
    public DbSet<RecurrenceRule> RecurrenceRules { get; set; }
    public DbSet<TodoOccurrence> TodoOccurrences { get; set; }
    public DbSet<RefreshToken> RefreshTokens { get; set; }
    public DbSet<PomodoroTemplate> PomodoroTemplates { get; set; }
    public DbSet<PomodoroTemplatePhase> PomodoroTemplatePhases { get; set; }
    public DbSet<PomodoroRun> PomodoroRuns { get; set; }
    public DbSet<PomodoroRunPhase> PomodoroRunPhases { get; set; }

    protected override void OnModelCreating(ModelBuilder builder)
    {
        base.OnModelCreating(builder);

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

            t.HasOne(todo => todo.User)
                .WithMany()
                .HasForeignKey(todo => todo.UserId)
                .OnDelete(DeleteBehavior.Cascade);

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

            t.HasQueryFilter(
                ACTIVE_TODOS_FILTER,
                todo => todo.Status != TaskStatus.Completed && todo.Status != TaskStatus.Cancelled
            );
            t.HasQueryFilter(USER_FILTER, todo => todo.UserId == userService.UserId);
        });

        builder.Entity<RecurrenceRule>(r =>
        {
            r.HasKey(p => p.Id);
            r.Property(p => p.Frequency).HasConversion<string>();
        });

        builder.Entity<TodoOccurrence>(t =>
        {
            t.Property(p => p.Remarks).IsRequired().HasMaxLength(4000);
            t.Property(p => p.CancellationReason).IsRequired().HasMaxLength(4000);
            t.Property<bool>("IsRescheduled").HasColumnName("is_rescheduled");

            t.HasIndex(o => new { o.TodoId, o.OccurrenceDate })
                .HasDatabaseName("ix_todo_occurrences_todo_id_occurrence_date");

            t.HasIndex(o => o.OccurrenceDate)
                .HasDatabaseName("ix_todo_occurrences_occurrence_date_active")
                .HasFilter("NOT is_cancelled AND NOT is_completed");

            t.HasQueryFilter(ACTIVE_OCCURRENCES_FILTER, o => !o.IsCancelled && !o.IsCompleted);
            t.HasQueryFilter(
                USER_FILTER,
                o => o.Todo != null && o.Todo.UserId == userService.UserId
            );
        });

        builder.Entity<RefreshToken>(t =>
        {
            t.HasKey(rt => rt.Id);
            t.Property(rt => rt.Token).IsRequired().HasMaxLength(512);
            t.Property(rt => rt.UserId).IsRequired().HasMaxLength(450);

            t.HasIndex(rt => rt.Token).IsUnique();
            t.HasOne(rt => rt.User)
                .WithMany()
                .HasForeignKey(rt => rt.UserId)
                .OnDelete(DeleteBehavior.Cascade);
        });

        builder.Entity<PomodoroTemplate>(t =>
        {
            t.HasKey(p => p.Id);
            t.Property(p => p.Name).IsRequired().HasMaxLength(200);
            t.Property(p => p.UserId).IsRequired().HasMaxLength(450);

            t.HasOne(p => p.User)
                .WithMany()
                .HasForeignKey(p => p.UserId)
                .OnDelete(DeleteBehavior.Cascade);

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

            r.HasOne(x => x.User)
                .WithMany()
                .HasForeignKey(x => x.UserId)
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
