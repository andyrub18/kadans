namespace Kadans.SharedKernel.Users;

public sealed record UserSummary(
    string Id,
    string? DisplayName,
    string? Email,
    string TimeZoneId,
    string Language,
    string? Username = null,
    bool EmailConfirmed = false,
    ClockFormat ClockFormat = ClockFormat.TwentyFourHour
);

/// <summary>Read-only view of users for other modules (implemented by Identity).</summary>
public interface IUserDirectory
{
    Task<UserSummary?> FindAsync(string userId, CancellationToken cancellationToken = default);

    /// <summary>Many accounts in one query (a job's batch); an unknown id is absent.</summary>
    Task<IReadOnlyDictionary<string, UserSummary>> FindManyAsync(IReadOnlyCollection<string> userIds, CancellationToken cancellationToken = default);

    /// <summary>
    /// An account by its username, or by its email address once confirmed: anyone can sign up with an address that is
    /// not theirs, so an unconfirmed one names nobody.
    /// </summary>
    Task<UserSummary?> FindByLoginAsync(string usernameOrEmail, CancellationToken cancellationToken = default);

    /// <summary>
    /// False once nobody can sign in to the account: it does not exist, it was deactivated, or its owner asked to
    /// delete it (closed at once, erased days later). What answers without a sign-in (the calendar feed's link) asks
    /// this, since no session is there to be refused.
    /// </summary>
    Task<bool> IsOpenAsync(string userId, CancellationToken cancellationToken = default);
}
