namespace Kadans.SharedKernel.Users;

public sealed record UserSummary(string Id, string? DisplayName, string? Email, string TimeZoneId);

/// <summary>Read-only view of users for other modules (implemented by Identity).</summary>
public interface IUserDirectory
{
    Task<UserSummary?> FindAsync(string userId, CancellationToken cancellationToken = default);
}
