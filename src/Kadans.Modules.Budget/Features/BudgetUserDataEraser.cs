using Kadans.Modules.Budget.Persistence;
using Kadans.SharedKernel.Persistence;
using Kadans.SharedKernel.Users;
using Microsoft.EntityFrameworkCore;

namespace Kadans.Modules.Budget.Features;

/// <summary>
/// An account being erased: every Budget record. The person's own finances, not Kadans' accounts, so nothing is kept
/// (the stores bill subscriptions, not Kadans).
/// </summary>
internal sealed class BudgetUserDataEraser(BudgetDbContext dbContext) : IUserDataEraser
{
    public async Task EraseAsync(string userId, CancellationToken cancellationToken = default)
    {
        await Retention.DeleteInBatchesAsync(dbContext.Transactions.IgnoreQueryFilters().Where(t => t.UserId == userId), t => t.Id, cancellationToken);
        await dbContext.RecurringTransactions.IgnoreQueryFilters().Where(r => r.UserId == userId).ExecuteDeleteAsync(cancellationToken);
        await dbContext.CategoryBudgets.IgnoreQueryFilters().Where(b => b.UserId == userId).ExecuteDeleteAsync(cancellationToken);
        await dbContext.Categories.IgnoreQueryFilters().Where(c => c.UserId == userId).ExecuteDeleteAsync(cancellationToken);
        await dbContext.Accounts.IgnoreQueryFilters().Where(a => a.UserId == userId).ExecuteDeleteAsync(cancellationToken);
        await dbContext.CurrencyRates.IgnoreQueryFilters().Where(r => r.UserId == userId).ExecuteDeleteAsync(cancellationToken);
        await dbContext.Profiles.IgnoreQueryFilters().Where(p => p.UserId == userId).ExecuteDeleteAsync(cancellationToken);
    }
}
