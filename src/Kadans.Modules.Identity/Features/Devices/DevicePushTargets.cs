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

    public async Task<IReadOnlyDictionary<string, IReadOnlyList<PushTarget>>> ForUsersAsync(IReadOnlyCollection<string> userIds, CancellationToken cancellationToken = default)
    {
        var devices = await dbContext
            .Devices.Where(d => userIds.Contains(d.UserId) && d.PushToken != null)
            .Select(d => new { d.UserId, Target = new PushTarget(d.Platform.ToString(), d.PushToken!) })
            .ToListAsync(cancellationToken);
        return devices.GroupBy(d => d.UserId).ToDictionary(g => g.Key, g => (IReadOnlyList<PushTarget>)[.. g.Select(d => d.Target)]);
    }

    public Task InvalidateAsync(string token, CancellationToken cancellationToken = default) =>
        dbContext
            .Devices.Where(d => d.PushToken == token)
            .ExecuteUpdateAsync(s => s.SetProperty(d => d.PushToken, (string?)null), cancellationToken);
}
