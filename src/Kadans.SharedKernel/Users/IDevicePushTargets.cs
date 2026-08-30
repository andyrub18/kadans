namespace Kadans.SharedKernel.Users;

public sealed record PushTarget(string Platform, string Token);

/// <summary>Push tokens of a user's registered devices (implemented by Identity, consumed by Notifications).</summary>
public interface IDevicePushTargets
{
    Task<IReadOnlyList<PushTarget>> ForUserAsync(string userId, CancellationToken cancellationToken = default);

    /// <summary>Forget a token the push provider reported as dead.</summary>
    Task InvalidateAsync(string token, CancellationToken cancellationToken = default);
}
