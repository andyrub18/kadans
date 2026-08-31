using Kadans.Modules.Notifications.Domain;
using Kadans.SharedKernel.Persistence;
using Microsoft.EntityFrameworkCore;

namespace Kadans.Modules.Notifications.Persistence;

internal sealed class NotificationsDbContext(DbContextOptions<NotificationsDbContext> options) : DbContext(options)
{
    public const string Schema = "notifications";

    public DbSet<Notification> Notifications => Set<Notification>();

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

        builder.Entity<Notification>(n =>
        {
            n.HasKey(x => x.Id);
            n.Property(x => x.UserId).IsRequired().HasMaxLength(450);
            n.Property(x => x.Kind).IsRequired().HasMaxLength(100);
            n.Property(x => x.Title).IsRequired().HasMaxLength(500);
            n.Property(x => x.Body).IsRequired().HasMaxLength(4000);
            n.Property(x => x.DataJson).HasColumnType("jsonb");

            n.HasIndex(x => new { x.UserId, x.CreatedAt })
                .HasDatabaseName("ix_notifications_user_id_created_at_desc")
                .IsDescending(false, true);

            n.HasIndex(x => x.UserId)
                .HasDatabaseName("ix_notifications_user_id_unread")
                .HasFilter("read_at IS NULL");
        });
    }
}
