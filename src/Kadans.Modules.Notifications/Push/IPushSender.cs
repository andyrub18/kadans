using Kadans.SharedKernel.Notifications;
using Kadans.SharedKernel.Users;

namespace Kadans.Modules.Notifications.Push;

internal interface IPushSender
{
    Task<PushOutcome> SendAsync(IReadOnlyList<PushTarget> targets, NotificationMessage message, CancellationToken cancellationToken = default);
}

/// <summary>What the provider answered, per device.</summary>
/// <param name="Dead">Tokens the provider reported as dead; the caller forgets them.</param>
internal sealed record PushOutcome(int Sent, int Failed, IReadOnlyList<string> Dead)
{
    public static PushOutcome AllSent(int devices) => new(devices, 0, []);
}
