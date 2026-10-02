using Kadans.Modules.Identity.Domain;
using Kadans.SharedKernel.Persistence;
using Microsoft.AspNetCore.DataProtection.EntityFrameworkCore;
using Microsoft.AspNetCore.Identity.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore;

namespace Kadans.Modules.Identity.Persistence;

internal sealed class IdentityModuleDbContext(DbContextOptions<IdentityModuleDbContext> options)
    : IdentityDbContext<ApplicationUser>(options), IDataProtectionKeyContext
{
    public const string Schema = "identity";

    public DbSet<RefreshToken> RefreshTokens => Set<RefreshToken>();
    public DbSet<Device> Devices => Set<Device>();

    /// <summary>
    /// The ASP.NET Core Data Protection key ring. It protects the tokens in emailed links (confirm email,
    /// reset password, confirm a new email); kept here, it survives container rebuilds and is in the backups.
    /// </summary>
    public DbSet<DataProtectionKey> DataProtectionKeys => Set<DataProtectionKey>();

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
            u.Property(x => x.PreferredLanguage).IsRequired().HasMaxLength(8);
        });

        builder.Entity<RefreshToken>(t =>
        {
            t.HasKey(rt => rt.Id);
            t.Property(rt => rt.TokenHash).IsRequired().HasMaxLength(64);
            t.Property(rt => rt.UserId).IsRequired().HasMaxLength(450);
            t.Property(rt => rt.RevokedReason).HasMaxLength(100);

            t.HasIndex(rt => rt.TokenHash).IsUnique();
            t.HasIndex(rt => rt.FamilyId);
            t.HasIndex(rt => new { rt.UserId, rt.IsActive });
            t.HasOne(rt => rt.User)
                .WithMany()
                .HasForeignKey(rt => rt.UserId)
                .OnDelete(DeleteBehavior.Cascade);
        });

        builder.Entity<Device>(d =>
        {
            d.HasKey(x => x.Id);
            d.Property(x => x.UserId).IsRequired().HasMaxLength(450);
            d.Property(x => x.Name).IsRequired().HasMaxLength(200);
            d.Property(x => x.PushToken).HasMaxLength(4096);
            d.Property(x => x.AppVersion).HasMaxLength(50);
            d.Property(x => x.Platform).HasConversion<string>();

            d.HasIndex(x => new { x.UserId, x.InstallationId }).IsUnique();
            d.HasIndex(x => x.SessionId);
            // Tokens are looked up by equality only (moving one to a new account, retiring a dead one), and may be
            // longer than a b-tree entry allows.
            d.HasIndex(x => x.PushToken).HasMethod("hash");
            d.HasOne<ApplicationUser>()
                .WithMany()
                .HasForeignKey(x => x.UserId)
                .OnDelete(DeleteBehavior.Cascade);
        });
    }
}
