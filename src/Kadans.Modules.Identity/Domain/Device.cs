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
    public string? AppVersion { get; set; }
    public DateTimeOffset RegisteredAt { get; init; } = DateTimeOffset.UtcNow;
    public DateTimeOffset LastSeenAt { get; set; } = DateTimeOffset.UtcNow;
}
