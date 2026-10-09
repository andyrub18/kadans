using Kadans.Modules.Identity.Persistence;
using Kadans.SharedKernel.Users;
using Microsoft.EntityFrameworkCore;

namespace Kadans.Modules.Identity.Features.Devices;

/// <summary>A device's reminder window, as Tasks hands it out (<see cref="IDeviceReminders"/>).</summary>
internal sealed class DeviceReminders(IdentityModuleDbContext dbContext) : IDeviceReminders
{
    public async Task<string?> PlatformOfAsync(string userId, Guid installationId, CancellationToken cancellationToken = default) =>
        await dbContext
            .Devices.Where(d => d.UserId == userId && d.InstallationId == installationId)
            .Select(d => d.Platform.ToString())
            .FirstOrDefaultAsync(cancellationToken);

    public Task RecordSyncAsync(
        string userId,
        Guid installationId,
        DateTimeOffset syncedAt,
        DateTimeOffset through,
        long version,
        CancellationToken cancellationToken = default
    ) =>
        dbContext
            .Devices.Where(d => d.UserId == userId && d.InstallationId == installationId)
            .ExecuteUpdateAsync(
                s => s.SetProperty(d => d.RemindersSyncedAt, syncedAt).SetProperty(d => d.RemindersThrough, through).SetProperty(d => d.RemindersVersion, version),
                cancellationToken
            );

    public Task StopAsync(string userId, Guid installationId, CancellationToken cancellationToken = default) =>
        dbContext
            .Devices.Where(d => d.UserId == userId && d.InstallationId == installationId)
            .ExecuteUpdateAsync(
                s => s.SetProperty(d => d.RemindersSyncedAt, (DateTimeOffset?)null)
                    .SetProperty(d => d.RemindersThrough, (DateTimeOffset?)null)
                    .SetProperty(d => d.RemindersVersion, 0L),
                cancellationToken
            );
}
