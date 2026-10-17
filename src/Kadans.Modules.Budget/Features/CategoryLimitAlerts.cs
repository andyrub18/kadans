using System.Globalization;
using Kadans.Modules.Budget.Domain;
using Kadans.Modules.Budget.Persistence;
using Kadans.SharedKernel.Notifications;
using Kadans.SharedKernel.Users;
using Microsoft.EntityFrameworkCore;

namespace Kadans.Modules.Budget.Features;

/// <summary>
/// Tells the person when a category's spending nears its limit (80%) and when it reaches it (100%): once each per
/// period, after any expense that adds to it, recorded by hand, edited or booked by a recurring rule. A change of rates
/// alone sends nothing; it counts at the next expense. Never fails the movement it follows.
/// </summary>
internal sealed class CategoryLimitAlerts(
    BudgetDbContext context,
    INotificationDispatcher dispatcher,
    IUserDirectory users,
    ILogger<CategoryLimitAlerts> logger
)
{
    public const string Kind = "budget.limit";

    /// <summary>What the alert opens: the budget.</summary>
    public const string Link = "kadans://budget";

    /// <summary>
    /// After expenses in <paramref name="categoryIds"/>: the limits among them that now call for an alert, in the period
    /// <paramref name="now"/> falls in. Reads across users (it also runs in the recurring job, with no caller).
    /// </summary>
    public async Task CheckAsync(string userId, IReadOnlyCollection<Guid> categoryIds, DateTimeOffset now, CancellationToken cancellationToken = default)
    {
        if (categoryIds.Count == 0)
            return;
        try
        {
            await CheckOrThrowAsync(userId, categoryIds, now, cancellationToken);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            logger.LogWarning(ex, "Could not check the category limits of user {UserId}", userId);
        }
    }

    private async Task CheckOrThrowAsync(string userId, IReadOnlyCollection<Guid> categoryIds, DateTimeOffset now, CancellationToken cancellationToken)
    {
        var limits = await context.CategoryBudgets
            .IgnoreQueryFilters()
            .Include(b => b.Category)
            .Where(b => b.UserId == userId && categoryIds.Contains(b.CategoryId))
            .ToListAsync(cancellationToken);
        limits.RemoveAll(l => l.Category is null or { IsArchived: true });
        if (limits.Count == 0)
            return;

        var user = await users.FindAsync(userId, cancellationToken);
        var zone = user is not null && TimeZoneInfo.TryFindSystemTimeZoneById(user.TimeZoneId, out var found) ? found : TimeZoneInfo.Utc;
        var rates = await RatesOf(context, userId, cancellationToken);
        var texts = BudgetTexts.Limit(user?.Language);

        var alerts = new List<NotificationMessage>();
        foreach (var limit in limits)
        {
            var (start, end) = CategoryBudget.PeriodAt(limit.Period, now, zone);
            var use = limit.Use(await SpendingAsync(context, userId, limit.CategoryId, start, end, cancellationToken), rates);
            if (limit.AlertDue(use.Percent, start) is not { } level)
                continue;
            limit.MarkAlerted(level, start);
            alerts.Add(Message(limit, use, level, texts));
        }
        if (alerts.Count == 0)
            return;

        await context.SaveChangesAsync(cancellationToken);
        foreach (var alert in alerts)
            await dispatcher.DispatchAsync(userId, alert, cancellationToken);
        logger.LogInformation("Sent {Count} category limit alert(s) to user {UserId}", alerts.Count, userId);
    }

    /// <summary>A category's expenses in [<paramref name="from"/>, <paramref name="to"/>), per currency.</summary>
    internal static async Task<List<(Currency Currency, decimal Amount)>> SpendingAsync(
        BudgetDbContext context,
        string userId,
        Guid categoryId,
        DateTimeOffset from,
        DateTimeOffset to,
        CancellationToken cancellationToken
    ) =>
        [.. (await context.Transactions
            .IgnoreQueryFilters()
            .Where(t => t.UserId == userId && t.CategoryId == categoryId && t.Kind == TransactionKind.Expense
                && t.OccurredAt >= from && t.OccurredAt < to)
            .GroupBy(t => t.Currency)
            .Select(g => new { Currency = g.Key, Amount = g.Sum(t => t.Amount) })
            .ToListAsync(cancellationToken)).Select(r => (r.Currency, r.Amount))];

    /// <summary>The person's base currency and indicative rates.</summary>
    internal static async Task<RateBook> RatesOf(BudgetDbContext context, string userId, CancellationToken cancellationToken)
    {
        var baseCurrency = await context.Profiles.IgnoreQueryFilters().Where(p => p.UserId == userId)
            .Select(p => (Currency?)p.BaseCurrency).FirstOrDefaultAsync(cancellationToken) ?? Currency.Htg;
        var rates = await context.CurrencyRates.IgnoreQueryFilters().Where(r => r.UserId == userId)
            .ToDictionaryAsync(r => r.Currency, r => r.RateInBase, cancellationToken);
        return new RateBook(baseCurrency, rates);
    }

    internal static NotificationMessage Message(CategoryBudget limit, LimitUse use, int level, LimitTexts texts)
    {
        var category = limit.Category!;
        var period = limit.Period switch
        {
            LimitPeriod.Week => texts.Week,
            LimitPeriod.Month => texts.Month,
            _ => texts.Year,
        };
        // "≈" when another currency was priced at the person's rates: an estimate, said as one.
        var spent = (use.Converted.Count > 0 ? "≈ " : "") + Amount(use.Spent, limit.Currency);
        var body = string.Format(level >= 100 ? texts.ReachedFormat : texts.NearFormat, use.Percent, period, spent, Amount(limit.Amount, limit.Currency));
        return new NotificationMessage(
            Kind,
            string.IsNullOrWhiteSpace(category.Icon) ? category.Name : $"{category.Icon} {category.Name}",
            body,
            new Dictionary<string, string>
            {
                ["categoryId"] = category.Id.ToString(),
                ["period"] = limit.Period.ToString(),
                ["level"] = level.ToString(CultureInfo.InvariantCulture),
                ["link"] = Link,
            }
        );
    }

    /// <summary>"15 000.00 HTG": as the apps write amounts (no-break spaces, so it never wraps inside).</summary>
    internal static string Amount(decimal amount, Currency currency) =>
        amount.ToString("#,0.00", CultureInfo.InvariantCulture).Replace(',', ' ') + " " + currency.ToString().ToUpperInvariant();
}

/// <summary>
/// A limit alert's wording ({0} the whole percent, {1} the period as the sentence needs it, {2} spent, {3} the limit).
/// </summary>
internal sealed record LimitTexts(string NearFormat, string ReachedFormat, string Week, string Month, string Year);

internal static class BudgetTexts
{
    public static LimitTexts Limit(string? language) =>
        language switch
        {
            "fr" => new(
                "{0} % de la limite {1} : {2} sur {3}.",
                "Limite {1} atteinte : {2} sur {3} ({0} %).",
                "de la semaine", "du mois", "de l'année"
            ),
            "ht" => new(
                "{0}% nan limit {1}: {2} sou {3}.",
                "Ou rive nan limit {1}: {2} sou {3} ({0}%).",
                "semèn sa a", "mwa sa a", "ane sa a"
            ),
            _ => new(
                "{0}% of this {1}'s limit: {2} of {3}.",
                "This {1}'s limit is reached: {2} of {3} ({0}%).",
                "week", "month", "year"
            ),
        };
}
