namespace Kadans.SharedKernel.Users;

/// <summary>
/// Whether an account may use Kadans on a phone: the mobile apps need a subscription, the desktop app is free.
/// Implemented by Billing (always yes while subscriptions are not required); Notifications asks it before pushing
/// to a phone, since the reminders are what a phone subscription buys.
/// </summary>
public interface IMobileAccess
{
    Task<bool> AllowsPhonesAsync(string userId, CancellationToken cancellationToken = default);
}
