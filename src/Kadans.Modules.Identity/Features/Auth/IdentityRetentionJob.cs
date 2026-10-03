using Kadans.Modules.Identity.Persistence;
using Kadans.SharedKernel.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Quartz;

namespace Kadans.Modules.Identity.Features.Auth;

internal sealed class IdentityRetentionOptions
{
    public const string SectionName = "Identity:Retention";

    /// <summary>
    /// How long a sign-in token is kept once expired. Every refresh leaves one behind, so a device in use leaves about
    /// one an hour; a few days of them still tell a replay from a typo when someone asks.
    /// </summary>
    public int ExpiredTokenGraceDays { get; set; } = 7;

    /// <summary>
    /// A device unseen this long goes, unless it still takes pushes: a phone that only shows reminders may never open
    /// the app, and Google reports an uninstalled app's token as dead at the next push, which removes it already.
    /// </summary>
    public int IdleDeviceDays { get; set; } = 180;
}

/// <summary>Nightly: expired sign-in tokens, and devices that can no longer be reached and are not used.</summary>
[DisallowConcurrentExecution]
internal sealed class IdentityRetentionJob(
    IdentityModuleDbContext dbContext,
    IOptions<IdentityRetentionOptions> options,
    ILogger<IdentityRetentionJob> logger
) : IJob
{
    public static readonly JobKey Key = new("identity-retention", "identity");

    public Task Execute(IJobExecutionContext context) => RunAsync(DateTimeOffset.UtcNow, context.CancellationToken);

    internal async Task RunAsync(DateTimeOffset now, CancellationToken cancellationToken)
    {
        var settings = options.Value;
        var tokensBefore = now.AddDays(-settings.ExpiredTokenGraceDays);
        var tokens = await Retention.DeleteInBatchesAsync(
            dbContext.RefreshTokens.Where(t => t.ExpireAtUtc < tokensBefore),
            t => t.Id,
            cancellationToken
        );

        var devicesBefore = now.AddDays(-settings.IdleDeviceDays);
        var devices = await Retention.DeleteInBatchesAsync(
            dbContext.Devices.Where(d => d.PushToken == null && d.LastSeenAt < devicesBefore),
            d => d.Id,
            cancellationToken
        );

        logger.LogInformation(
            "Retention: removed {Tokens} sign-in token(s) expired over {TokenDays} days ago and {Devices} device(s) unseen for {DeviceDays} days",
            tokens, settings.ExpiredTokenGraceDays, devices, settings.IdleDeviceDays
        );
    }
}
