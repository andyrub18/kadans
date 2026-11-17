using Kadans.Modules.Budget.Domain;
using Kadans.Modules.Budget.Persistence;
using Kadans.SharedKernel.Text;
using Kadans.SharedKernel.Users;
using Microsoft.EntityFrameworkCore;

namespace Kadans.Modules.Budget.Features;

/// <summary>
/// "Export my data": the budget as CSV files (ARCHITECTURE → "Statements in, data out"). Every movement, oldest
/// first, with its account, category and note in words, not ids; the accounts with their balances; the categories
/// with their limits; the recurring movements; and the person's currency and rates. Dates are the person's own days
/// (their time zone); headers are English in every language, for whatever reads the file next.
/// </summary>
internal sealed class BudgetUserDataExporter(BudgetDbContext dbContext, IUserDirectory users) : IUserDataExporter
{
    public async Task<IReadOnlyList<ExportedFile>> ExportAsync(string userId, CancellationToken cancellationToken = default)
    {
        var user = await users.FindAsync(userId, cancellationToken);
        var zone = user is not null && TimeZoneInfo.TryFindSystemTimeZoneById(user.TimeZoneId, out var found) ? found : TimeZoneInfo.Utc;

        // Named by the user, as the eraser does: an export never depends on whose request carries it.
        var accounts = await dbContext.Accounts.IgnoreQueryFilters().AsNoTracking().Where(a => a.UserId == userId).OrderBy(a => a.CreatedAt).ToListAsync(cancellationToken);
        var allCategories = await dbContext.Categories.IgnoreQueryFilters().AsNoTracking().Where(c => c.UserId == userId).OrderBy(c => c.CreatedAt).ToListAsync(cancellationToken);
        var categories = allCategories.ToDictionary(c => c.Id, c => c.Name);
        var movements = await dbContext
            .Transactions.IgnoreQueryFilters()
            .AsNoTracking()
            .Where(t => t.UserId == userId)
            .OrderBy(t => t.OccurredAt)
            .ThenBy(t => t.CreatedAt)
            .Select(t => new
            {
                t.AccountId, t.Kind, t.Amount, t.Currency, t.OccurredAt, t.CategoryId, t.Note,
                t.TransferAccountId, t.TransferAmount, t.RecurringTransactionId, t.ImportKey,
            })
            .ToListAsync(cancellationToken);
        var byId = accounts.ToDictionary(a => a.Id);

        var movementsFile = new Csv("Date", "Account", "Currency", "Kind", "Amount", "Category", "Note", "To account", "Amount received", "Currency received", "Recurring", "Imported");
        var balances = accounts.ToDictionary(a => a.Id, a => a.InitialBalance);
        foreach (var m in movements)
        {
            var to = m.TransferAccountId is { } toId ? byId.GetValueOrDefault(toId) : null;
            movementsFile.Row(
                TimeZoneInfo.ConvertTime(m.OccurredAt, zone).ToString("yyyy-MM-dd"),
                byId.GetValueOrDefault(m.AccountId)?.Name,
                Code(m.Currency),
                m.Kind.ToString(),
                Csv.Number(m.Amount),
                m.CategoryId is { } categoryId ? categories.GetValueOrDefault(categoryId) : null,
                m.Note,
                to?.Name,
                m.Kind == TransactionKind.Transfer ? Csv.Number(m.TransferAmount ?? m.Amount) : null,
                to is null ? null : Code(to.Currency),
                m.RecurringTransactionId is null ? null : "yes",
                m.ImportKey is null ? null : "yes"
            );

            // An account's balance: what it started with, what came in, what went out (a transfer does both).
            if (balances.ContainsKey(m.AccountId))
                balances[m.AccountId] += m.Kind == TransactionKind.Income ? m.Amount : -m.Amount;
            if (m.Kind == TransactionKind.Transfer && m.TransferAccountId is { } destination && balances.ContainsKey(destination))
                balances[destination] += m.TransferAmount ?? m.Amount;
        }

        var accountsFile = new Csv("Account", "Type", "Currency", "Initial balance", "Balance", "Archived");
        foreach (var account in accounts)
            accountsFile.Row(account.Name, account.Type.ToString(), Code(account.Currency), Csv.Number(account.InitialBalance), Csv.Number(balances[account.Id]), account.IsArchived ? "yes" : null);

        var limits = await dbContext.CategoryBudgets.IgnoreQueryFilters().AsNoTracking().Where(b => b.UserId == userId).ToDictionaryAsync(b => b.CategoryId, cancellationToken);
        var categoriesFile = new Csv("Category", "Kind", "Icon", "Archived", "Limit", "Limit currency", "Limit period");
        foreach (var category in allCategories)
        {
            var limit = limits.GetValueOrDefault(category.Id);
            categoriesFile.Row(
                category.Name, category.Kind.ToString(), category.Icon, category.IsArchived ? "yes" : null,
                limit is null ? null : Csv.Number(limit.Amount), limit is null ? null : Code(limit.Currency), limit?.Period.ToString()
            );
        }

        var rules = await dbContext.RecurringTransactions.IgnoreQueryFilters().AsNoTracking().Where(r => r.UserId == userId).OrderBy(r => r.CreatedAt).ToListAsync(cancellationToken);
        var rulesFile = new Csv("Kind", "Account", "Amount", "Currency", "Category", "Note", "To account", "Amount received", "Repeats (RRULE)", "Time zone", "First on", "Next on", "Active");
        foreach (var rule in rules)
        {
            var to = rule.TransferAccountId is { } toId ? byId.GetValueOrDefault(toId) : null;
            rulesFile.Row(
                rule.Kind.ToString(),
                byId.GetValueOrDefault(rule.AccountId)?.Name,
                Csv.Number(rule.Amount),
                Code(rule.Currency),
                rule.CategoryId is { } categoryId ? categories.GetValueOrDefault(categoryId) : null,
                rule.Note,
                to?.Name,
                rule.Kind == TransactionKind.Transfer ? Csv.Number(rule.TransferAmount ?? rule.Amount) : null,
                rule.Rrule,
                rule.TimeZoneId,
                TimeZoneInfo.ConvertTime(rule.StartDate, zone).ToString("yyyy-MM-dd"),
                rule.NextOccurrenceAt is { } next ? TimeZoneInfo.ConvertTime(next, zone).ToString("yyyy-MM-dd") : null,
                rule.IsActive ? "yes" : "no"
            );
        }

        // The currency the person counts in, and what one unit of each other is worth in it, as they set it.
        var profile = await dbContext.Profiles.IgnoreQueryFilters().AsNoTracking().FirstOrDefaultAsync(p => p.UserId == userId, cancellationToken);
        var rates = await dbContext.CurrencyRates.IgnoreQueryFilters().AsNoTracking().Where(r => r.UserId == userId).OrderBy(r => r.Currency).ToListAsync(cancellationToken);
        var baseCurrency = Code(profile?.BaseCurrency ?? Currency.Htg);
        var currenciesFile = new Csv("Currency", "Worth", "In", "Set on");
        currenciesFile.Row(baseCurrency, "1", baseCurrency, null);
        foreach (var rate in rates)
            currenciesFile.Row(
                Code(rate.Currency), rate.RateInBase.ToString("0.######", System.Globalization.CultureInfo.InvariantCulture), baseCurrency,
                TimeZoneInfo.ConvertTime(rate.UpdatedAt, zone).ToString("yyyy-MM-dd")
            );

        return
        [
            new ExportedFile("budget/movements.csv", movementsFile.ToBytes()),
            new ExportedFile("budget/accounts.csv", accountsFile.ToBytes()),
            new ExportedFile("budget/categories.csv", categoriesFile.ToBytes()),
            new ExportedFile("budget/recurring.csv", rulesFile.ToBytes()),
            new ExportedFile("budget/currencies.csv", currenciesFile.ToBytes()),
        ];
    }

    private static string Code(Currency currency) => currency.ToString().ToUpperInvariant();
}
