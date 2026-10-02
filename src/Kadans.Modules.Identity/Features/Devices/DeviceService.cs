using Kadans.Modules.Identity.Contracts;
using Kadans.Modules.Identity.Domain;
using Kadans.Modules.Identity.Persistence;
using Kadans.SharedKernel.Errors;
using Kadans.SharedKernel.Security;
using Microsoft.EntityFrameworkCore;
using OneOf;
using OneOf.Types;

namespace Kadans.Modules.Identity.Features.Devices;

/// <summary>
/// Registers client installations and their push tokens for the current user. A device belongs to the sign-in
/// session that registered it (ending the session removes it), and an installation or a push token to one account
/// at a time: on a phone shared or handed over, the next account to sign in takes it, and the previous one stops
/// getting its reminders there.
/// </summary>
internal sealed class DeviceService(IdentityModuleDbContext dbContext, ICurrentUserService currentUser)
{
    public async Task<OneOf<ApplicationError, DeviceResponse>> Register(Guid installationId, RegisterDeviceRequest request)
    {
        if (currentUser.UserId is null)
            return Unauthorized();

        if (string.IsNullOrWhiteSpace(request.Name))
            return new ValidationError(ErrorTypes.ValidationError, "Validation failed for registering device.", [("NameRequired", "Name is required.")]);

        var userId = currentUser.UserId;
        var pushToken = string.IsNullOrWhiteSpace(request.PushToken) ? null : request.PushToken;

        await dbContext.Devices.Where(d => d.InstallationId == installationId && d.UserId != userId).ExecuteDeleteAsync();
        if (pushToken is not null)
        {
            await dbContext
                .Devices.Where(d => d.PushToken == pushToken && !(d.UserId == userId && d.InstallationId == installationId))
                .ExecuteUpdateAsync(s => s.SetProperty(d => d.PushToken, (string?)null));
        }

        var device = await dbContext.Devices.FirstOrDefaultAsync(d => d.UserId == userId && d.InstallationId == installationId);
        if (device is null)
        {
            device = new Device { InstallationId = installationId, UserId = userId };
            dbContext.Devices.Add(device);
        }

        device.Platform = request.Platform;
        device.Name = request.Name.Trim();
        device.PushToken = pushToken;
        device.SessionId = Guid.TryParse(currentUser.SessionId, out var sessionId) ? sessionId : null;
        device.AppVersion = request.AppVersion;
        device.LastSeenAt = DateTimeOffset.UtcNow;

        await dbContext.SaveChangesAsync();
        return ToResponse(device);
    }

    public async Task<OneOf<ApplicationError, List<DeviceResponse>>> List()
    {
        if (currentUser.UserId is null)
            return Unauthorized();

        var devices = await dbContext.Devices.Where(d => d.UserId == currentUser.UserId).OrderByDescending(d => d.LastSeenAt).ToListAsync();
        return devices.ConvertAll(ToResponse);
    }

    public async Task<OneOf<ApplicationError, Success>> Remove(Guid installationId)
    {
        if (currentUser.UserId is null)
            return Unauthorized();

        var deleted = await dbContext.Devices.Where(d => d.UserId == currentUser.UserId && d.InstallationId == installationId).ExecuteDeleteAsync();
        if (deleted == 0)
            return new ApplicationError(ErrorTypes.DeviceNotFound, $"Device {installationId} is not registered.");

        return new Success();
    }

    private static DeviceResponse ToResponse(Device d) =>
        new(d.InstallationId, d.Platform, d.Name, d.PushToken is not null, d.AppVersion, d.RegisteredAt, d.LastSeenAt);

    private static ApplicationError Unauthorized() => new(ErrorTypes.Unauthorized, "Unable to resolve current user.");
}
