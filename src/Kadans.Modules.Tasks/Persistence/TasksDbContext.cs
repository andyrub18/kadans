using Kadans.Modules.Tasks.Domain;
using Kadans.Modules.Tasks.Features.Reminders;
using Kadans.SharedKernel.Persistence;
using Kadans.SharedKernel.Realtime;
using Microsoft.EntityFrameworkCore;
using TaskStatus = Kadans.Modules.Tasks.Domain.TaskStatus;

namespace Kadans.Modules.Tasks.Persistence;

internal sealed class TasksDbContext(DbContextOptions<TasksDbContext> options) : UserScopedDbContext(options)
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
    public DbSet<ReminderChange> ReminderChanges => Set<ReminderChange>();

    /// <summary>Tells the account's apps a moment later that its reminders changed; handed over when the context is rented.</summary>
    internal ReminderSignals? Signals { get; set; }

    /// <summary>Tells the account's open apps that what their screens show changed; handed over when the context is rented.</summary>
    internal ILiveChanges? Live { get; set; }

    // The jobs' bookkeeping: a reminder stamped sent, the horizon moved on. Nothing anyone sees changes.
    private static readonly HashSet<string> Stamps =
    [
        nameof(TodoOccurrence.NotifiedAt),
        nameof(TodoOccurrence.StartNotifiedAt),
        nameof(Todo.OccurrencesGeneratedThrough),
    ];

    // What a phone's reminder window is made of: neither the stamps change it, nor a note on an occurrence.
    private static readonly HashSet<string> Bookkeeping = [.. Stamps, nameof(TodoOccurrence.Remarks)];

    private const string BumpVersion =
        "INSERT INTO " + Schema + ".reminder_changes (user_id, version, changed_at) VALUES ({0}, 1, {1}) "
        + "ON CONFLICT (user_id) DO UPDATE SET version = reminder_changes.version + 1, changed_at = EXCLUDED.changed_at";

    /// <summary>
    /// A person's change to a todo, its rule or an occurrence moves the account's reminders version
    /// (<see cref="ReminderChange"/>) in the same transaction, and signals its apps. In one place, so no feature can
    /// forget it; a job has no current user and moves nothing. A window read in one snapshot holds a version exactly
    /// when it holds the changes up to it. Once saved, a person's change also tells the account's open apps to load
    /// their screens again (<see cref="ChangesScreens"/>).
    /// </summary>
    public override async Task<int> SaveChangesAsync(bool acceptAllChangesOnSuccess, CancellationToken cancellationToken = default)
    {
        var shown = ChangesScreens();
        var saved = await SaveWithRemindersAsync(acceptAllChangesOnSuccess, cancellationToken);
        if (shown is not null)
            Live?.Changed(shown, LiveAreas.Todos);
        return saved;
    }

    public override int SaveChanges(bool acceptAllChangesOnSuccess)
    {
        var shown = ChangesScreens();
        var saved = SaveWithReminders(acceptAllChangesOnSuccess);
        if (shown is not null)
            Live?.Changed(shown, LiveAreas.Todos);
        return saved;
    }

    private async Task<int> SaveWithRemindersAsync(bool acceptAllChangesOnSuccess, CancellationToken cancellationToken)
    {
        var userId = ChangesReminders();
        if (userId is null)
            return await base.SaveChangesAsync(acceptAllChangesOnSuccess, cancellationToken);

        var own = Database.CurrentTransaction is null ? await Database.BeginTransactionAsync(cancellationToken) : null;
        try
        {
            await Database.ExecuteSqlRawAsync(BumpVersion, [userId, DateTimeOffset.UtcNow], cancellationToken);
            var saved = await base.SaveChangesAsync(acceptAllChangesOnSuccess, cancellationToken);
            if (own is not null)
                await own.CommitAsync(cancellationToken);
            Signals?.Changed(userId);
            return saved;
        }
        finally
        {
            if (own is not null)
                await own.DisposeAsync();
        }
    }

    private int SaveWithReminders(bool acceptAllChangesOnSuccess)
    {
        var userId = ChangesReminders();
        if (userId is null)
            return base.SaveChanges(acceptAllChangesOnSuccess);

        using var own = Database.CurrentTransaction is null ? Database.BeginTransaction() : null;
        Database.ExecuteSqlRaw(BumpVersion, userId, DateTimeOffset.UtcNow);
        var saved = base.SaveChanges(acceptAllChangesOnSuccess);
        own?.Commit();
        Signals?.Changed(userId);
        return saved;
    }

    /// <summary>
    /// The person making this save, when it changes what the apps' screens show: a todo, its rule, an occurrence (its
    /// note too), a focus template. Not a session, which has its own event; never a job's stamps. Reads never save, so
    /// the apps' reloads never signal again.
    /// </summary>
    private string? ChangesScreens() =>
        CurrentUserId is { } userId
        && ChangeTracker
            .Entries()
            .Any(entry =>
                entry.Entity is not (PomodoroRun or PomodoroRunPhase)
                && entry.State switch
                {
                    EntityState.Added or EntityState.Deleted => true,
                    EntityState.Modified => entry.Properties.Any(p => p.IsModified && !Stamps.Contains(p.Metadata.Name)),
                    _ => false,
                }
            )
            ? userId
            : null;

    /// <summary>The person making this save, when it touches what a reminder is made of.</summary>
    private string? ChangesReminders() =>
        CurrentUserId is { } userId
        && ChangeTracker
            .Entries()
            .Any(entry =>
                entry.Entity is Todo or TodoOccurrence or RecurrenceRule
                && entry.State switch
                {
                    EntityState.Added or EntityState.Deleted => true,
                    EntityState.Modified => entry.Properties.Any(p => p.IsModified && !Bookkeeping.Contains(p.Metadata.Name)),
                    _ => false,
                }
            )
            ? userId
            : null;

    protected override void ConfigureConventions(ModelConfigurationBuilder configurationBuilder)
    {
        base.ConfigureConventions(configurationBuilder);
        configurationBuilder.StoreDateTimeOffsetsAsUtc(Database.ProviderName);
    }

    protected override void OnModelCreating(ModelBuilder builder)
    {
        base.OnModelCreating(builder);

        builder.HasDefaultSchema(Schema);
        builder.UseSnakeCaseNames();

        builder.Entity<Todo>(t =>
        {
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
            t.HasQueryFilter(USER_FILTER, todo => todo.UserId == CurrentUserId);
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
            t.Property(p => p.Remarks).HasMaxLength(TodoOccurrence.MaxNoteLength);
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

            // What the reminder job scans: the reminders before the start, then the "starts now" ones.
            t.HasIndex(o => o.NotifyAt)
                .HasDatabaseName("ix_todo_occurrences_notify_due")
                .HasFilter("status = 'Pending' AND notified_at IS NULL AND notify_at IS NOT NULL");
            t.HasIndex(o => o.ScheduledAt, "ix_todo_occurrences_start_due")
                .HasFilter("status = 'Pending' AND start_notified_at IS NULL AND notify_at IS NOT NULL");

            t.HasQueryFilter(ACTIVE_OCCURRENCES_FILTER, o => o.Status == OccurrenceStatus.Pending);
            t.HasQueryFilter(
                USER_FILTER,
                o => o.Todo != null && o.Todo.UserId == CurrentUserId
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

            t.HasQueryFilter(p => p.UserId == CurrentUserId);
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

            // The occurrence a session is for, so its end can offer to mark it done. Gone (retention, a rule edited),
            // the session stays without it.
            r.HasOne<TodoOccurrence>()
                .WithMany()
                .HasForeignKey(x => x.OccurrenceId)
                .OnDelete(DeleteBehavior.SetNull);

            r.HasMany(x => x.Phases)
                .WithOne()
                .HasForeignKey(x => x.PomodoroRunId)
                .OnDelete(DeleteBehavior.Cascade);

            // Postgres' xmin as row version: a watching client and the deadline watcher both try to
            // advance a hands-free run the instant its phase ends – exactly one may win, or the phase
            // change is announced twice (and a looping run gets its next lap appended twice).
            r.Property<uint>("Version").IsRowVersion();

            // What the deadline watcher scans: hands-free runs to advance, manual runs to tell their time is up.
            r.HasIndex(x => x.PhaseEndsAt)
                .HasDatabaseName("ix_pomodoro_runs_auto_advance_due")
                .HasFilter("status = 'Active' AND auto_advance");
            r.HasIndex(x => x.PhaseEndsAt, "ix_pomodoro_runs_manual_due")
                .HasFilter("status = 'Active' AND NOT auto_advance");
            r.HasIndex(x => x.FinishBy, "ix_pomodoro_runs_finish_due")
                .HasFilter("status IN ('Active', 'Paused')");

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

            r.HasQueryFilter(x => x.UserId == CurrentUserId);
        });

        builder.Entity<PomodoroRunPhase>(p =>
        {
            p.HasKey(x => x.Id);
            p.Property(x => x.Type).HasConversion<string>();

            p.HasIndex(x => new { x.PomodoroRunId, x.Order })
                .HasDatabaseName("ix_pomodoro_run_phases_run_order");
        });

        // One row per account; read across accounts by the reminder job, so no user filter.
        builder.Entity<ReminderChange>(c =>
        {
            c.HasKey(x => x.UserId).HasName("pk_reminder_changes");
            c.Property(x => x.UserId).HasMaxLength(450);
        });
    }
}
