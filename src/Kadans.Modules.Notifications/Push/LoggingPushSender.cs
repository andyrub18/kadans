using Kadans.SharedKernel.Notifications;
using Kadans.SharedKernel.Users;

namespace Kadans.Modules.Notifications.Push;

internal sealed class LoggingPushSender(ILogger<LoggingPushSender> logger) : IPushSender
{
    public Task<PushOutcome> SendAsync(IReadOnlyList<PushEnvelope> envelopes, CancellationToken cancellationToken = default)
    {
        foreach (var group in envelopes.GroupBy(e => e.Message))
        {
            logger.LogInformation("PUSH (not sent) to {Count} device(s) [{Platforms}] {Kind}", group.Count(), string.Join(",", group.Select(e => e.Target.Platform)), group.Key.Kind);
            // What the person wrote (a todo's title) stays out of the shipped logs: Debug is below their minimum.
            logger.LogDebug("PUSH content | {Title} — {Body}", group.Key.Title, group.Key.Body);
        }
        return Task.FromResult(PushOutcome.AllSent(envelopes.Count)); // counted as sent: the dashboards work in Development too
    }
}
