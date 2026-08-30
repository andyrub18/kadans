using Kadans.SharedKernel.Notifications;
using Kadans.SharedKernel.Users;

namespace Kadans.Modules.Notifications.Push;

internal interface IPushSender
{
    /// <returns>Tokens the provider reported as dead; the caller forgets them.</returns>
    Task<IReadOnlyList<string>> SendAsync(IReadOnlyList<PushTarget> targets, NotificationMessage message, CancellationToken cancellationToken = default);
}
