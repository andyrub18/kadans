using Kadans.Modules.Identity.Domain;
using Kadans.Modules.Identity.Persistence;
using Kadans.SharedKernel.Users;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using Quartz;

namespace Kadans.Modules.Identity.Features.Account;

/// <summary>
/// Erases the accounts whose grace period is over: every other module's data first (<see cref="IUserDataEraser"/>),
/// then the account itself (sign-ins, 2FA, sessions, devices go with it), then one last email. A failure leaves the
/// account for the next run, which finishes it: every eraser can run again. What stays is the deletion record, with
/// the id and the dates only, so a restored backup can be cleaned again (DEPLOYMENT → Restore).
/// </summary>
[DisallowConcurrentExecution]
internal sealed class AccountErasureJob(
    IdentityModuleDbContext dbContext,
    UserManager<ApplicationUser> userManager,
    IEnumerable<IUserDataEraser> erasers,
    IdentityEmails emails,
    TimeProvider time,
    ILogger<AccountErasureJob> logger
) : IJob
{
    public static readonly JobKey Key = new("account-erasure", "identity");

    public Task Execute(IJobExecutionContext context) => RunAsync(context.CancellationToken);

    internal async Task RunAsync(CancellationToken cancellationToken)
    {
        var now = time.GetUtcNow();
        var due = await dbContext
            .AccountDeletions.Where(d => d.ErasedAt == null && d.EraseAfter <= now)
            .OrderBy(d => d.EraseAfter)
            .Take(100)
            .ToListAsync(cancellationToken);

        foreach (var deletion in due)
        {
            try
            {
                await EraseAsync(deletion, cancellationToken);
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                logger.LogError(ex, "Erasing account {UserId} failed; the next run tries again", deletion.UserId);
            }
        }
    }

    private async Task EraseAsync(AccountDeletion deletion, CancellationToken cancellationToken)
    {
        var user = await userManager.FindByIdAsync(deletion.UserId);
        // The last email needs the address and the name, which are about to go.
        (string Email, string Greeting, string? Language)? farewell =
            user is { Email: { Length: > 0 } email } ? (email, IdentityEmails.Greeting(user), user.PreferredLanguage) : null;

        foreach (var eraser in erasers)
            await eraser.EraseAsync(deletion.UserId, cancellationToken);

        if (user is not null)
        {
            var deleted = await userManager.DeleteAsync(user);
            if (!deleted.Succeeded)
                throw new InvalidOperationException($"Deleting the user failed: {string.Join("; ", deleted.Errors.Select(e => e.Code))}");
        }

        deletion.ErasedAt = time.GetUtcNow();
        await dbContext.SaveChangesAsync(cancellationToken);
        logger.LogWarning("Account {UserId} erased with everything in it, as asked on {RequestedAt:O}", deletion.UserId, deletion.RequestedAt);

        if (farewell is { } last)
        {
            try
            {
                await emails.SendErasedAsync(last.Email, last.Greeting, last.Language, cancellationToken);
            }
            catch (Exception ex)
            {
                logger.LogWarning(ex, "Could not send the erasure notice for account {UserId}", deletion.UserId);
            }
        }
    }
}
