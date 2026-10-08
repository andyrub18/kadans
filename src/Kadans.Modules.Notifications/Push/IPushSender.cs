using Kadans.SharedKernel.Notifications;
using Kadans.SharedKernel.Users;

namespace Kadans.Modules.Notifications.Push;

/// <summary>One message for one device.</summary>
internal sealed record PushEnvelope(PushTarget Target, NotificationMessage Message);

internal interface IPushSender
{
    /// <summary>Up to <see cref="MaxPerCall"/> messages, each to its own device, in one call to the provider.</summary>
    Task<PushOutcome> SendAsync(IReadOnlyList<PushEnvelope> envelopes, CancellationToken cancellationToken = default);

    /// <summary>Firebase's SendEach takes at most 500 messages.</summary>
    const int MaxPerCall = 500;
}

/// <summary>What the provider answered, per device.</summary>
/// <param name="Dead">Tokens the provider reported as dead; the caller forgets them.</param>
internal sealed record PushOutcome(int Sent, int Failed, IReadOnlyList<string> Dead)
{
    public static PushOutcome AllSent(int devices) => new(devices, 0, []);
}
