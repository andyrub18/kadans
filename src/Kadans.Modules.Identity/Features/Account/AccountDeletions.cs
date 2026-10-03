using Kadans.Modules.Identity.Contracts;
using Kadans.Modules.Identity.Domain;
using Kadans.Modules.Identity.Features.Auth;
using Kadans.Modules.Identity.Persistence;
using Kadans.Modules.Identity.Security;
using Kadans.SharedKernel.Errors;
using Kadans.SharedKernel.Security;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using OneOf;

namespace Kadans.Modules.Identity.Features.Account;

internal sealed class AccountDeletionOptions
{
    public const string SectionName = "Identity:AccountDeletion";

    /// <summary>Days between the request and the erasure: the account is closed meanwhile, and can be kept.</summary>
    public int GraceDays { get; set; } = 7;
}

/// <summary>
/// "Delete my account" (ARCHITECTURE → Account deletion). The account closes at once: every session ends, its devices
/// go, and a sign-in only offers to keep it. <see cref="AccountErasureJob"/> erases it with everything in it once
/// the grace period is over. Asking takes proof: the password, or for an account without one (Google only), a link
/// sent to its address, which is also how the web page (for people without the app) works.
/// </summary>
internal sealed class AccountDeletions(
    IdentityModuleDbContext dbContext,
    UserManager<ApplicationUser> userManager,
    ICurrentUserService currentUser,
    Sessions sessions,
    Authentication authentication,
    JwtProvider jwtProvider,
    IdentityEmails emails,
    IOptions<AccountDeletionOptions> options,
    TimeProvider time,
    ILogger<AccountDeletions> logger
)
{
    /// <summary>From the app: with the password the account closes now; without one, a confirmation link goes out.</summary>
    public async Task<OneOf<ApplicationError, DeleteAccountResponse>> Request(DeleteAccountRequest request, CancellationToken cancellationToken)
    {
        var user = currentUser.UserId is null ? null : await userManager.FindByIdAsync(currentUser.UserId);
        if (user is null)
            return new ApplicationError(ErrorTypes.Unauthorized, "Unable to resolve current user.");

        if (!await userManager.HasPasswordAsync(user))
        {
            if (string.IsNullOrWhiteSpace(user.Email))
                return new ApplicationError(ErrorTypes.EmailNotSet, "This account has no email address.");
            await emails.SendDeletionLinkAsync(user, cancellationToken);
            return new DeleteAccountResponse(null, user.Email);
        }

        if (string.IsNullOrEmpty(request.CurrentPassword))
            return new ValidationError(ErrorTypes.ValidationError, "Validation failed for deleting the account.", [("CurrentPasswordRequired", "Enter your current password.")]);
        if (await CheckPasswordAsync(user, request.CurrentPassword) is { } wrong)
            return wrong;

        return new DeleteAccountResponse(await ScheduleAsync(user, cancellationToken));
    }

    /// <summary>The web page: always the same answer, so it cannot tell which addresses have an account.</summary>
    public async Task RequestByEmail(string email, CancellationToken cancellationToken)
    {
        var user = string.IsNullOrWhiteSpace(email) ? null : await userManager.FindByEmailAsync(email.Trim());
        if (user is not null && !await IsScheduledAsync(user.Id))
            await emails.SendDeletionLinkAsync(user, cancellationToken);
    }

    /// <summary>The page the emailed link opens: who is asking, before the button is pressed.</summary>
    public async Task<ApplicationUser?> FindByLinkAsync(string userId, string token)
    {
        var user = await userManager.FindByIdAsync(userId);
        var decoded = IdentityEmails.Decode(token);
        return user is not null && decoded is not null
            && await userManager.VerifyUserTokenAsync(user, TokenOptions.DefaultProvider, IdentityEmails.DeleteAccountPurpose, decoded)
            ? user
            : null;
    }

    /// <summary>The button on that page (a POST: a mail scanner opening the link must not delete anything).</summary>
    public async Task<OneOf<ApplicationError, (ApplicationUser User, DateTimeOffset EraseAfter)>> ConfirmByLink(string userId, string token, CancellationToken cancellationToken)
    {
        var user = await FindByLinkAsync(userId, token);
        if (user is null)
            return new ApplicationError(ErrorTypes.InvalidToken, "The confirmation link is invalid or expired.");

        return (user, await ScheduleAsync(user, cancellationToken));
    }

    /// <summary>"Keep my account", from the offer a sign-in made: the erasure is off, and a session starts.</summary>
    public async Task<OneOf<ApplicationError, LoginResponse>> Restore(RestoreAccountRequest request)
    {
        var userId = jwtProvider.ValidateRestoreToken(request.RestoreToken);
        var user = userId is null ? null : await userManager.FindByIdAsync(userId);
        if (user is null)
            return new ApplicationError(ErrorTypes.InvalidToken, "This offer to keep the account has expired. Sign in again.");

        var removed = await dbContext.AccountDeletions.Where(d => d.UserId == user.Id && d.ErasedAt == null).ExecuteDeleteAsync();
        if (removed > 0)
            logger.LogInformation("User {UserId} kept their account before its erasure", user.Id);

        return await authentication.StartSessionAsync(user);
    }

    /// <summary>When an account asked now would be erased (for the confirmation page, before the button).</summary>
    public DateTimeOffset EraseAfterIfAskedNow() => time.GetUtcNow().AddDays(options.Value.GraceDays);

    public Task<bool> IsScheduledAsync(string userId) =>
        dbContext.AccountDeletions.AnyAsync(d => d.UserId == userId && d.ErasedAt == null);

    /// <summary>Closes the account now and sets its erasure; asking again keeps the first date.</summary>
    private async Task<DateTimeOffset> ScheduleAsync(ApplicationUser user, CancellationToken cancellationToken)
    {
        var existing = await dbContext.AccountDeletions.FirstOrDefaultAsync(d => d.UserId == user.Id && d.ErasedAt == null, cancellationToken);
        var now = time.GetUtcNow();
        var eraseAfter = existing?.EraseAfter ?? now.AddDays(options.Value.GraceDays);
        if (existing is null)
        {
            // A record left by an erasure of this id cannot exist (ids are never reused), but stay safe.
            await dbContext.AccountDeletions.Where(d => d.UserId == user.Id).ExecuteDeleteAsync(cancellationToken);
            dbContext.AccountDeletions.Add(new AccountDeletion { UserId = user.Id, RequestedAt = now, EraseAfter = eraseAfter });
            await dbContext.SaveChangesAsync(cancellationToken);
        }

        var ended = await sessions.EndAllAsync(user.Id, "account deletion requested", cancellationToken);
        logger.LogWarning("User {UserId} asked to delete their account: closed, {Sessions} session(s) ended, erasure on {EraseAfter:O}", user.Id, ended.SessionIds.Count, eraseAfter);

        try
        {
            await emails.SendDeletionScheduledAsync(user, eraseAfter, cancellationToken);
        }
        catch (Exception ex)
        {
            logger.LogWarning(ex, "Could not tell user {UserId} about the scheduled erasure", user.Id);
        }

        return eraseAfter;
    }

    /// <summary>Like every password asked again: wrong ones count toward the lock.</summary>
    private async Task<ApplicationError?> CheckPasswordAsync(ApplicationUser user, string password)
    {
        if (await userManager.IsLockedOutAsync(user))
            return AccountLock.Refusal(user);
        if (await userManager.CheckPasswordAsync(user, password))
        {
            await userManager.ResetAccessFailedCountAsync(user);
            return null;
        }

        await userManager.AccessFailedAsync(user);
        return new ApplicationError(ErrorTypes.InvalidCredentials, "The current password is not correct.");
    }
}
