namespace Kadans.SharedKernel.Users;

/// <summary>
/// The reminder windows devices fetch to ring reminders themselves (ARCHITECTURE → "Reminders ring on the phone").
/// Implemented by Identity, which owns devices; used by Tasks, which owns reminders.
/// </summary>
public interface IDeviceReminders
{
    /// <summary>The platform of this installation of the account's ("Android", "Ios", …), or null when it has none.</summary>
    Task<string?> PlatformOfAsync(string userId, Guid installationId, CancellationToken cancellationToken = default);

    /// <summary>
    /// This installation has the account's reminders through <paramref name="through"/>, as of <paramref name="syncedAt"/>,
    /// up to the account's reminders version <paramref name="version"/>.
    /// </summary>
    Task RecordSyncAsync(string userId, Guid installationId, DateTimeOffset syncedAt, DateTimeOffset through, long version, CancellationToken cancellationToken = default);

    /// <summary>It no longer rings them itself (the permission went, the app was told to stop): pushes resume at once.</summary>
    Task StopAsync(string userId, Guid installationId, CancellationToken cancellationToken = default);
}
