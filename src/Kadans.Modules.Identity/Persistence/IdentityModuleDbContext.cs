using Kadans.Modules.Identity.Domain;
using Kadans.SharedKernel.Persistence;
using Microsoft.AspNetCore.Identity.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore;

namespace Kadans.Modules.Identity.Persistence;

internal sealed class IdentityModuleDbContext(DbContextOptions<IdentityModuleDbContext> options)
    : IdentityDbContext<ApplicationUser>(options)
{
    public const string Schema = "identity";

    public DbSet<RefreshToken> RefreshTokens => Set<RefreshToken>();

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

        builder.Entity<ApplicationUser>(u =>
        {
            u.Property(x => x.DisplayName).HasMaxLength(100);
            u.Property(x => x.TimeZoneId).IsRequired().HasMaxLength(64);
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
    }
}
