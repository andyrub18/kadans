using Kadans.Modules.Budget.Domain;
using Kadans.SharedKernel.Persistence;
using Kadans.SharedKernel.Security;
using Microsoft.EntityFrameworkCore;

namespace Kadans.Modules.Budget.Persistence;

internal sealed class BudgetDbContext(
    DbContextOptions<BudgetDbContext> options,
    ICurrentUserService userService
) : DbContext(options)
{
    public const string Schema = "budget";

    public const string USER_FILTER = "UserFilter";

    public DbSet<Account> Accounts => Set<Account>();
    public DbSet<Category> Categories => Set<Category>();
    public DbSet<CategoryBudget> CategoryBudgets => Set<CategoryBudget>();
    public DbSet<Transaction> Transactions => Set<Transaction>();
    public DbSet<RecurringTransaction> RecurringTransactions => Set<RecurringTransaction>();

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

        builder.Entity<Account>(a =>
        {
            a.Property(p => p.Name).IsRequired().HasMaxLength(200);
            // Users live in the Identity module: reference by id only, no FK/navigation.
            a.Property(p => p.UserId).IsRequired().HasMaxLength(450);
            a.Property(p => p.Currency).HasConversion<string>().HasMaxLength(8);
            a.Property(p => p.Type).HasConversion<string>().HasMaxLength(32);
            a.Property(p => p.InitialBalance).HasPrecision(16, 2);
            a.HasIndex(p => new { p.UserId, p.IsArchived });
            a.HasQueryFilter(USER_FILTER, x => x.UserId == userService.UserId);
        });

        builder.Entity<Category>(c =>
        {
            c.Property(p => p.Name).IsRequired().HasMaxLength(200);
            c.Property(p => p.UserId).IsRequired().HasMaxLength(450);
            c.Property(p => p.Kind).HasConversion<string>().HasMaxLength(16);
            c.Property(p => p.Icon).HasMaxLength(16);
            c.HasIndex(p => new { p.UserId, p.Kind });
            c.HasQueryFilter(USER_FILTER, x => x.UserId == userService.UserId);
        });

        builder.Entity<CategoryBudget>(b =>
        {
            b.Property(p => p.UserId).IsRequired().HasMaxLength(450);
            b.Property(p => p.Currency).HasConversion<string>().HasMaxLength(8);
            b.Property(p => p.MonthlyLimit).HasPrecision(16, 2);
            b.HasOne(p => p.Category).WithMany().HasForeignKey(p => p.CategoryId).OnDelete(DeleteBehavior.Cascade);
            b.HasIndex(p => new { p.UserId, p.CategoryId }).IsUnique();
            b.HasQueryFilter(USER_FILTER, x => x.UserId == userService.UserId);
        });

        builder.Entity<Transaction>(t =>
        {
            t.Property(p => p.UserId).IsRequired().HasMaxLength(450);
            t.Property(p => p.Kind).HasConversion<string>().HasMaxLength(16);
            t.Property(p => p.Currency).HasConversion<string>().HasMaxLength(8);
            t.Property(p => p.Amount).HasPrecision(16, 2);
            t.Property(p => p.TransferAmount).HasPrecision(16, 2);
            t.Property(p => p.Note).IsRequired().HasMaxLength(1000);

            t.HasOne(p => p.Account).WithMany().HasForeignKey(p => p.AccountId).OnDelete(DeleteBehavior.Cascade);
            t.HasOne(p => p.TransferAccount).WithMany().HasForeignKey(p => p.TransferAccountId).OnDelete(DeleteBehavior.Cascade);
            t.HasOne(p => p.Category).WithMany().HasForeignKey(p => p.CategoryId).OnDelete(DeleteBehavior.SetNull);

            // The two hot paths: an account's ledger, and a month of activity.
            t.HasIndex(p => new { p.UserId, p.AccountId, p.OccurredAt })
                .HasDatabaseName("ix_transactions_user_account_occurred_desc")
                .IsDescending(false, false, true);
            t.HasIndex(p => new { p.UserId, p.OccurredAt })
                .HasDatabaseName("ix_transactions_user_occurred_desc")
                .IsDescending(false, true);
            t.HasIndex(p => p.TransferAccountId);

            t.HasQueryFilter(USER_FILTER, x => x.UserId == userService.UserId);
        });

        builder.Entity<RecurringTransaction>(r =>
        {
            r.Property(p => p.UserId).IsRequired().HasMaxLength(450);
            r.Property(p => p.Kind).HasConversion<string>().HasMaxLength(16);
            r.Property(p => p.Currency).HasConversion<string>().HasMaxLength(8);
            r.Property(p => p.Amount).HasPrecision(16, 2);
            r.Property(p => p.Note).IsRequired().HasMaxLength(1000);
            r.Property(p => p.Rrule).IsRequired().HasMaxLength(512);
            r.Property(p => p.TimeZoneId).IsRequired().HasMaxLength(64);

            r.HasOne(p => p.Account).WithMany().HasForeignKey(p => p.AccountId).OnDelete(DeleteBehavior.Cascade);
            r.HasOne(p => p.Category).WithMany().HasForeignKey(p => p.CategoryId).OnDelete(DeleteBehavior.SetNull);

            // What the materializing job scans.
            r.HasIndex(p => p.GeneratedThrough).HasFilter("is_active = true");

            r.HasQueryFilter(USER_FILTER, x => x.UserId == userService.UserId);
        });
    }
}
