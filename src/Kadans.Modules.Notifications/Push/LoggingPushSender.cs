using Kadans.SharedKernel.Notifications;
using Kadans.SharedKernel.Users;

namespace Kadans.Modules.Notifications.Push;

internal sealed class LoggingPushSender(ILogger<LoggingPushSender> logger) : IPushSender
{
    public Task<IReadOnlyList<string>> SendAsync(IReadOnlyList<PushTarget> targets, NotificationMessage message, CancellationToken cancellationToken = default)
    {
        logger.LogInformation(
            "PUSH (not sent) to {Count} device(s) [{Platforms}] | {Title} — {Body}",
            targets.Count,
            string.Join(",", targets.Select(t => t.Platform)),
            message.Title,
            message.Body
        );
        return Task.FromResult<IReadOnlyList<string>>([]);
    }
}
