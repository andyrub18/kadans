using Kadans.Modules.Billing.Contracts;
using Kadans.Modules.Billing.Domain;
using Kadans.Modules.Billing.Persistence;
using Kadans.SharedKernel.Errors;
using Kadans.SharedKernel.Security;
using Kadans.SharedKernel.Users;
using Microsoft.EntityFrameworkCore;
using OneOf;
using OneOf.Types;

namespace Kadans.Modules.Billing.Features;

/// <summary>
/// The accounts whose phones are free, kept by an admin: the closed test's testers, Google's reviewers, family. A
/// change applies at once (no restart): <see cref="MobileAccess"/> forgets the account's answer, and the app lets the
/// person in at its next look (opening it, or Restore).
/// </summary>
internal sealed class FreeAccounts(
    BillingDbContext dbContext,
    IUserDirectory users,
    MobileAccess access,
    ICurrentUserService currentUser,
    TimeProvider time,
    ILogger<FreeAccounts> logger
)
{
    public async Task<List<FreeAccountResponse>> List(CancellationToken cancellationToken)
    {
        var accounts = await dbContext.FreeAccounts.IgnoreQueryFilters().AsNoTracking().OrderBy(f => f.AddedAt).ToListAsync(cancellationToken);
        var list = new List<FreeAccountResponse>(accounts.Count);
        foreach (var account in accounts)
            list.Add(Response(account, await users.FindAsync(account.UserId, cancellationToken)));
        return list;
    }

    /// <summary>By username, or by a confirmed email address. Adding an account already there changes nothing.</summary>
    public async Task<OneOf<ApplicationError, FreeAccountResponse>> Add(AddFreeAccountRequest request, CancellationToken cancellationToken)
    {
        if (currentUser.UserId is not { } adminId)
            return new ApplicationError(ErrorTypes.Unauthorized, "Unable to resolve current user.");

        var user = string.IsNullOrWhiteSpace(request.Account) ? null : await users.FindByLoginAsync(request.Account.Trim(), cancellationToken);
        if (user is null)
            return new ApplicationError(ErrorTypes.UserNotFound, "No account has that username or confirmed email address.");

        var account = await dbContext.FreeAccounts.IgnoreQueryFilters().FirstOrDefaultAsync(f => f.UserId == user.Id, cancellationToken);
        if (account is null)
        {
            account = dbContext.FreeAccounts.Add(new FreeAccount { UserId = user.Id, AddedBy = adminId, AddedAt = time.GetUtcNow() }).Entity;
            await dbContext.SaveChangesAsync(cancellationToken);
            access.Changed(user.Id);
            logger.LogInformation("Admin {AdminId} made the phones of {UserId} free", adminId, user.Id);
        }
        return Response(account, user);
    }

    /// <summary>Back to needing a subscription (if subscriptions are required). Removing one not there changes nothing.</summary>
    public async Task<OneOf<ApplicationError, Success>> Remove(string userId, CancellationToken cancellationToken)
    {
        if (currentUser.UserId is not { } adminId)
            return new ApplicationError(ErrorTypes.Unauthorized, "Unable to resolve current user.");

        var removed = await dbContext.FreeAccounts.IgnoreQueryFilters().Where(f => f.UserId == userId).ExecuteDeleteAsync(cancellationToken);
        if (removed > 0)
        {
            access.Changed(userId);
            logger.LogInformation("Admin {AdminId} removed {UserId} from the free accounts", adminId, userId);
        }
        return new Success();
    }

    private static FreeAccountResponse Response(FreeAccount account, UserSummary? user) =>
        new(account.UserId, user?.Username, user?.Email, user?.EmailConfirmed ?? false, user?.DisplayName, account.AddedAt);
}
