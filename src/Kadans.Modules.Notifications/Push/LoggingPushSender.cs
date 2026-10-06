using Kadans.SharedKernel.Notifications;
using Kadans.SharedKernel.Users;

namespace Kadans.Modules.Notifications.Push;

internal sealed class LoggingPushSender(ILogger<LoggingPushSender> logger) : IPushSender
{
    public Task<PushOutcome> SendAsync(IReadOnlyList<PushTarget> targets, NotificationMessage message, CancellationToken cancellationToken = default)
    {
        logger.LogInformation("PUSH (not sent) to {Count} device(s) [{Platforms}] {Kind}", targets.Count, string.Join(",", targets.Select(t => t.Platform)), message.Kind);
        // What the person wrote (a todo's title) stays out of the shipped logs: Debug is below their minimum.
        logger.LogDebug("PUSH content | {Title} — {Body}", message.Title, message.Body);
        return Task.FromResult(PushOutcome.AllSent(targets.Count)); // counted as sent: the dashboards work in Development too
    }
}
