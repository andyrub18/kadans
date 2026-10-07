using Kadans.SharedKernel.Notifications;
using Kadans.SharedKernel.Users;
using Microsoft.Extensions.Options;

namespace Kadans.Modules.Notifications.Push;

/// <summary>
/// Load tests only (<c>Push:Provider=Simulated</c>, refused unless <c>LoadTest:Enabled</c>): Firebase as far as timing
/// goes, without a device. Like <c>SendEachAsync</c>, at most 500 messages per call, each call taking
/// <see cref="PushOptions.SimulatedOptions.LatencyMilliseconds"/>; every message counts as sent. Real Firebase would
/// refuse the seeded accounts' made-up tokens, and nothing must reach a real phone.
/// </summary>
internal sealed class SimulatedPushSender(IOptions<PushOptions> options) : IPushSender
{
    internal const int MessagesPerCall = 500;

    public async Task<PushOutcome> SendAsync(IReadOnlyList<PushTarget> targets, NotificationMessage message, CancellationToken cancellationToken = default)
    {
        var calls = (targets.Count + MessagesPerCall - 1) / MessagesPerCall;
        for (var i = 0; i < calls; i++)
            await Task.Delay(options.Value.Simulated.LatencyMilliseconds, cancellationToken);
        return PushOutcome.AllSent(targets.Count);
    }
}
