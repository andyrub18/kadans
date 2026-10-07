using Kadans.SharedKernel.Recurrence;
using Npgsql;

namespace Kadans.LoadTest.Seeder;

/// <summary>
/// A budget per account: HTG base, cash and a bank account (sometimes USD savings), the usual categories, three
/// monthly limits, a salary and a rent that recur monthly, and about 60 transactions over the last three months.
/// </summary>
internal sealed class BudgetCopies : IAsyncDisposable
{
    private readonly Copy[] all;
    private readonly Dictionary<(int Day, string Zone), string> monthly = [];

    private BudgetCopies(Copy profiles, Copy accounts, Copy categories, Copy limits, Copy rates, Copy recurring, Copy transactions)
    {
        Profiles = profiles;
        Accounts = accounts;
        Categories = categories;
        Limits = limits;
        Rates = rates;
        Recurring = recurring;
        Transactions = transactions;
        all = [profiles, accounts, categories, limits, rates, recurring, transactions];
    }

    public Copy Profiles { get; }
    public Copy Accounts { get; }
    public Copy Categories { get; }
    public Copy Limits { get; }
    public Copy Rates { get; }
    public Copy Recurring { get; }
    public Copy Transactions { get; }

    private static readonly (string Name, string Icon)[] Expenses =
        [("Food", "🍚"), ("Transport", "🚌"), ("Rent", "🏠"), ("Phone", "📱"), ("Health", "💊"), ("Leisure", "🎬")];

    public static async Task<BudgetCopies> OpenAsync(NpgsqlDataSource db) =>
        new(
            await Copy.OpenAsync(db, "budget.profiles", "user_id", "base_currency", "updated_at"),
            await Copy.OpenAsync(db, "budget.accounts", "id", "user_id", "name", "currency", "type", "initial_balance", "is_archived", "created_at", "updated_at"),
            await Copy.OpenAsync(db, "budget.categories", "id", "user_id", "name", "kind", "icon", "is_archived", "created_at", "updated_at"),
            await Copy.OpenAsync(db, "budget.category_budgets", "id", "user_id", "category_id", "currency", "monthly_limit", "updated_at"),
            await Copy.OpenAsync(db, "budget.currency_rates", "id", "user_id", "currency", "rate_in_base", "updated_at"),
            await Copy.OpenAsync(db, "budget.recurring_transactions",
                "id", "user_id", "account_id", "kind", "amount", "currency", "category_id", "note", "rrule", "time_zone_id", "start_date",
                "generated_through", "is_active", "created_at", "updated_at"),
            await Copy.OpenAsync(db, "budget.transactions",
                "id", "user_id", "account_id", "kind", "amount", "currency", "occurred_at", "category_id", "note", "transfer_account_id",
                "transfer_amount", "recurring_transaction_id", "created_at", "updated_at")
        );

    public void AddUser(string userId, string zone, DateTimeOffset now, Random rng, Seeder seeder)
    {
        var since = now.AddDays(-180);
        Profiles.Row(userId, "Htg", since);

        var cash = Guid.CreateVersion7(since);
        var bank = Guid.CreateVersion7(since);
        Accounts.Row(cash, userId, "Cash", "Htg", "Cash", (decimal)rng.Next(500, 20_000), false, since, since);
        Accounts.Row(bank, userId, "Bank", "Htg", "Bank", (decimal)rng.Next(5_000, 200_000), false, since, since);
        if (rng.Next(10) < 3)
        {
            Accounts.Row(Guid.CreateVersion7(since), userId, "Savings", "Usd", "Savings", (decimal)rng.Next(50, 3_000), false, since, since);
            Rates.Row(Guid.CreateVersion7(since), userId, "Usd", 132.50m, since);
        }

        var salary = Guid.CreateVersion7(since);
        Categories.Row(salary, userId, "Salary", "Income", "💼", false, since, since);
        Categories.Row(Guid.CreateVersion7(since), userId, "Other income", "Income", "💰", false, since, since);
        var expenses = new Guid[Expenses.Length];
        for (var e = 0; e < Expenses.Length; e++)
        {
            expenses[e] = Guid.CreateVersion7(since);
            Categories.Row(expenses[e], userId, Expenses[e].Name, "Expense", Expenses[e].Icon, false, since, since);
        }
        Limits.Row(Guid.CreateVersion7(since), userId, expenses[0], "Htg", 15_000m, since); // food
        Limits.Row(Guid.CreateVersion7(since), userId, expenses[1], "Htg", 4_000m, since);  // transport
        Limits.Row(Guid.CreateVersion7(since), userId, expenses[3], "Htg", 1_500m, since);  // phone

        // Salary on the 25th, rent on the 1st; the job keeps each rule's generated_through close to now.
        var salaryRule = Guid.CreateVersion7(since);
        Recurring.Row(salaryRule, userId, bank, "Income", (decimal)rng.Next(20, 80) * 1000, "Htg", salary, "Salary",
            MonthlyRrule(25, zone, since), zone, since, now.AddMinutes(-rng.Next(0, 24 * 60)), true, since, since);
        Recurring.Row(Guid.CreateVersion7(since), userId, cash, "Expense", (decimal)rng.Next(8, 25) * 1000, "Htg", expenses[2], "Rent",
            MonthlyRrule(1, zone, since), zone, since, now.AddMinutes(-rng.Next(0, 24 * 60)), true, since, since);

        for (var m = 1; m <= 3; m++)
        {
            var paid = now.AddDays(-30 * m);
            Transactions.Row(Guid.CreateVersion7(paid), userId, bank, "Income", (decimal)35_000, "Htg", paid, salary, "Salary", null, null, salaryRule, paid, paid);
        }

        for (var t = 0; t < 57; t++)
        {
            var at = now.AddMinutes(-rng.Next(1, 90 * 24 * 60));
            var roll = rng.Next(100);
            if (roll < 4)
                Transactions.Row(Guid.CreateVersion7(at), userId, bank, "Transfer", (decimal)rng.Next(10, 100) * 100, "Htg", at, null, "", cash,
                    (decimal)rng.Next(10, 100) * 100, null, at, at);
            else if (roll < 12)
                Transactions.Row(Guid.CreateVersion7(at), userId, cash, "Income", (decimal)rng.Next(5, 200) * 100, "Htg", at, null, "", null, null, null, at, at);
            else
                Transactions.Row(Guid.CreateVersion7(at), userId, rng.Next(2) == 0 ? cash : bank, "Expense", (decimal)rng.Next(1, 100) * 50, "Htg", at,
                    expenses[rng.Next(expenses.Length)], "", null, null, null, at, at);
        }
    }

    private string MonthlyRrule(int day, string zone, DateTimeOffset start)
    {
        if (monthly.TryGetValue((day, zone), out var rrule))
            return rrule;
        rrule = RecurrenceSchedule.Create(new RecurrenceSpec(Frequency.Monthly, ByMonthDay: [day]), start, zone)
            .Match<string>(e => throw new InvalidOperationException(e.ErrorMessage), s => s.Rrule);
        monthly[(day, zone)] = rrule;
        return rrule;
    }

    public async ValueTask DisposeAsync()
    {
        foreach (var copy in all)
            await copy.DisposeAsync();
    }
}
