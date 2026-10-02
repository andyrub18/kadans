using Kadans.Modules.Identity.Domain;
using Kadans.SharedKernel.Errors;

namespace Kadans.Modules.Identity.Security;

/// <summary>
/// The two locks ASP.NET Identity's lockout carries. Deactivation (by an admin, or the person themselves) locks an
/// account for good; wrong passwords or codes lock it for a few minutes. Only deactivation ends sessions, refuses
/// Google sign-in and withholds the reset email: a stranger typing wrong passwords against a username must not sign
/// its owner out everywhere, nor keep them from proving who they are.
/// </summary>
internal static class AccountLock
{
    /// <summary>
    /// Deactivated: locked until the end of time. The database keeps microseconds, so that end comes back a tick
    /// short of <see cref="DateTimeOffset.MaxValue"/>; any lock longer than a millennium counts.
    /// </summary>
    public static bool IsDeactivated(ApplicationUser user) =>
        user.LockoutEnd is { } end && end > DateTimeOffset.UtcNow.AddYears(1000);

    public static ApplicationError Deactivated() => new(ErrorTypes.UserInactive, "User is deactivated");

    /// <summary>The answer for a locked account, whichever lock it is.</summary>
    public static ApplicationError Refusal(ApplicationUser user) =>
        IsDeactivated(user) ? Deactivated() : new ApplicationError(ErrorTypes.AccountLockedOut, "Too many failed attempts. Try again in a few minutes.");
}
