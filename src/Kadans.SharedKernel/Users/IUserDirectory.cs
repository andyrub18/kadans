namespace Kadans.SharedKernel.Users;

public sealed record UserSummary(
    string Id,
    string? DisplayName,
    string? Email,
    string TimeZoneId,
    string Language,
    string? Username = null,
    bool EmailConfirmed = false
);

/// <summary>Read-only view of users for other modules (implemented by Identity).</summary>
public interface IUserDirectory
{
    Task<UserSummary?> FindAsync(string userId, CancellationToken cancellationToken = default);

    /// <summary>
    /// An account by its username, or by its email address once confirmed: anyone can sign up with an address that is
    /// not theirs, so an unconfirmed one names nobody.
    /// </summary>
    Task<UserSummary?> FindByLoginAsync(string usernameOrEmail, CancellationToken cancellationToken = default);
}
