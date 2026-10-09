namespace Kadans.SharedKernel.Users;

/// <param name="RemindersSyncedAt">When the device last fetched its reminder window to ring itself; null if it does not.</param>
/// <param name="RemindersThrough">How far that window reaches.</param>
/// <param name="RemindersVersion">The account's reminders version that window holds.</param>
public sealed record PushTarget(
    string Platform,
    string Token,
    DateTimeOffset? RemindersSyncedAt = null,
    DateTimeOffset? RemindersThrough = null,
    long RemindersVersion = 0
);

/// <summary>Push tokens of a user's registered devices (implemented by Identity, consumed by Notifications).</summary>
public interface IDevicePushTargets
{
    Task<IReadOnlyList<PushTarget>> ForUserAsync(string userId, CancellationToken cancellationToken = default);

    /// <summary>Many accounts' devices in one query; an account without any is absent.</summary>
    Task<IReadOnlyDictionary<string, IReadOnlyList<PushTarget>>> ForUsersAsync(IReadOnlyCollection<string> userIds, CancellationToken cancellationToken = default);

    /// <summary>Forget a token the push provider reported as dead.</summary>
    Task InvalidateAsync(string token, CancellationToken cancellationToken = default);
}
