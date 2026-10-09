namespace Kadans.Modules.Identity.Domain;

public enum DevicePlatform
{
    Android,
    Ios,
    Windows,
    MacOs,
    Linux,
    Web,
}

/// <summary>An installation of a client app, identified by a client-generated installation id.</summary>
internal sealed class Device
{
    public Guid Id { get; init; } = Guid.CreateVersion7();
    public Guid InstallationId { get; set; }
    public required string UserId { get; set; }
    public DevicePlatform Platform { get; set; }
    public string Name { get; set; } = string.Empty;
    public string? PushToken { get; set; }

    /// <summary>
    /// The sign-in session that registered this device (the access token's session). Ending that session removes
    /// the device, so a signed-out phone stops getting reminders. Null for a device registered before sessions
    /// were recorded; it gets one at its next registration.
    /// </summary>
    public Guid? SessionId { get; set; }
    public string? AppVersion { get; set; }

    /// <summary>
    /// When this device last fetched its reminder window to ring reminders itself, and how far that window reaches
    /// (ARCHITECTURE → "Reminders ring on the phone"); both null for a device that does not. The push skips a reminder
    /// such a device already has.
    /// </summary>
    public DateTimeOffset? RemindersSyncedAt { get; set; }
    public DateTimeOffset? RemindersThrough { get; set; }

    /// <summary>The account's reminders version that window holds (Tasks counts one per change a person makes).</summary>
    public long RemindersVersion { get; set; }
    public DateTimeOffset RegisteredAt { get; init; } = DateTimeOffset.UtcNow;
    public DateTimeOffset LastSeenAt { get; set; } = DateTimeOffset.UtcNow;
}
