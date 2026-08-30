using Kadans.Modules.Identity.Persistence;
using Kadans.SharedKernel.Users;
using Microsoft.EntityFrameworkCore;

namespace Kadans.Modules.Identity.Features.Devices;

internal sealed class DevicePushTargets(IdentityModuleDbContext dbContext) : IDevicePushTargets
{
    public async Task<IReadOnlyList<PushTarget>> ForUserAsync(string userId, CancellationToken cancellationToken = default) =>
        await dbContext
            .Devices.Where(d => d.UserId == userId && d.PushToken != null)
            .Select(d => new PushTarget(d.Platform.ToString(), d.PushToken!))
            .ToListAsync(cancellationToken);

    public Task InvalidateAsync(string token, CancellationToken cancellationToken = default) =>
        dbContext
            .Devices.Where(d => d.PushToken == token)
            .ExecuteUpdateAsync(s => s.SetProperty(d => d.PushToken, (string?)null), cancellationToken);
}
