using Kadans.Modules.Billing.Domain;
using Kadans.SharedKernel.Persistence;
using Microsoft.EntityFrameworkCore;

namespace Kadans.Modules.Billing.Persistence;

internal sealed class BillingDbContext(DbContextOptions<BillingDbContext> options) : UserScopedDbContext(options)
{
    public const string Schema = "billing";

    public DbSet<StoreSubscription> Subscriptions => Set<StoreSubscription>();

    public DbSet<FreeAccount> FreeAccounts => Set<FreeAccount>();

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

        builder.Entity<StoreSubscription>(s =>
        {
            // Users live in the Identity module: reference by id only, no FK/navigation.
            s.Property(p => p.UserId).IsRequired().HasMaxLength(450);
            s.Property(p => p.Store).HasConversion<string>().HasMaxLength(16);
            s.Property(p => p.State).HasConversion<string>().HasMaxLength(16);
            // Google's purchase tokens run to a few hundred characters.
            s.Property(p => p.StoreKey).IsRequired().HasMaxLength(1024);
            s.Property(p => p.ProductId).IsRequired().HasMaxLength(200);
            // A purchase belongs to one account.
            s.HasIndex(p => new { p.Store, p.StoreKey }).IsUnique();
            s.HasIndex(p => p.UserId);
            s.HasQueryFilter(x => x.UserId == CurrentUserId);
        });

        builder.Entity<FreeAccount>(f =>
        {
            // Named here: UseSnakeCaseNames above ran before this key existed.
            f.HasKey(p => p.UserId).HasName("pk_free_accounts");
            f.Property(p => p.UserId).HasMaxLength(450);
            f.Property(p => p.AddedBy).IsRequired().HasMaxLength(450);
            f.HasQueryFilter(x => x.UserId == CurrentUserId);
        });
    }
}
