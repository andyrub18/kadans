namespace Kadans.Modules.Identity.Domain;

/// <summary>
/// One link in a refresh-token family. The raw token is only ever returned to the client;
/// the database keeps its SHA-256. A family is one login session (one device); refreshing
/// rotates within the family, and presenting an already-rotated token is treated as theft
/// and revokes the whole family.
/// </summary>
internal sealed class RefreshToken
{
    public int Id { get; set; }
    public required string TokenHash { get; set; }
    public Guid FamilyId { get; set; }
    public DateTimeOffset CreatedAtUtc { get; set; }
    public DateTimeOffset ExpireAtUtc { get; set; }
    public bool IsActive { get; set; }
    public DateTimeOffset? RevokedAtUtc { get; set; }
    public string? RevokedReason { get; set; }
    public required string UserId { get; set; }
    public ApplicationUser User { get; set; } = null!;

    public bool IsExpired => ExpireAtUtc <= DateTimeOffset.UtcNow;

    public void Revoke(string reason)
    {
        IsActive = false;
        RevokedAtUtc = DateTimeOffset.UtcNow;
        RevokedReason = reason;
    }
}
