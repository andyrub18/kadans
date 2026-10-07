using System.Security.Cryptography;
using System.Text;
using Kadans.SharedKernel.Recurrence;
using Microsoft.AspNetCore.Identity;
using Npgsql;

namespace Kadans.LoadTest.Seeder;

/// <summary>
/// What 50,000 people using Kadans for a few months leave in the database (docs/LOADTEST.md → The data):
/// accounts with a live session and a phone, about five active todos each on real recurrence rules (the app's own
/// engine computes every occurrence), their history (done, skipped, cancelled), the notification centre, and a
/// budget. Account <c>i</c> is <c>lt{i}</c>, password <see cref="DefaultPassword"/>, session <c>lt-{tag}-{i}</c>.
/// </summary>
internal sealed class Seeder(NpgsqlDataSource db, int randomSeed)
{
    public const string DefaultPassword = "LoadTest123!";

    private const int HorizonDays = 30; // Tasks:OccurrenceHorizonDays

    private readonly Random rng = new(randomSeed);
    private readonly DateTimeOffset now = DateTimeOffset.FromUnixTimeSeconds(DateTimeOffset.UtcNow.ToUnixTimeSeconds());
    private readonly Dictionary<(string Pattern, string Zone, DateTimeOffset Start), (string Rrule, DateTimeOffset[] At)> schedules = [];

    private static readonly (string Zone, int Weight)[] Zones =
        [("America/Port-au-Prince", 80), ("America/New_York", 10), ("America/Toronto", 5), ("Europe/Paris", 5)];

    private static readonly (string Language, int Weight)[] Languages = [("ht", 50), ("fr", 30), ("en", 20)];

    private static readonly string[] Titles =
    [
        "Morning run", "Read 20 pages", "Take medicine", "Call mom", "Team meeting", "Deep work", "Water the plants",
        "Pay the electricity", "Study French", "Pray", "Gym", "Plan the week", "Clean the kitchen", "Market",
        "Review the budget", "Homework with the kids", "Bible study", "Back up the laptop", "Choir practice", "Walk",
    ];

    // How rules are shared out (weights); about 40 occurrence rows per todo with 75 days of history.
    private static readonly (string Pattern, int Weight)[] Patterns =
        [("daily", 22), ("weekdays", 10), ("weekly", 33), ("every-2-days", 5), ("monthly", 15), ("once", 15)];

    private static readonly int[] Leads = [5, 10, 15, 30, 60];

    public async Task SeedAsync(int users, int historyDays, string password)
    {
        // An initial admin may be there (the API creates it at startup); the seeded accounts must not be.
        await using (var check = db.CreateCommand("SELECT count(*) FROM identity.asp_net_users WHERE user_name LIKE 'lt%'"))
        {
            if ((long)(await check.ExecuteScalarAsync())! > 0)
                throw new InvalidOperationException("The database is already seeded: seed a fresh one (docs/LOADTEST.md).");
        }

        var passwordHash = new PasswordHasher<object>().HashPassword(new object(), password);
        var horizon = now.AddDays(HorizonDays);
        Console.WriteLine($"seeding {users} accounts, {historyDays} days of history, horizon {horizon:u}");

        await using var accounts = await Copy.OpenAsync(db, "identity.asp_net_users",
            "id", "display_name", "time_zone_id", "created_at", "updated_at", "user_name", "normalized_user_name", "email",
            "normalized_email", "email_confirmed", "password_hash", "security_stamp", "concurrency_stamp",
            "phone_number_confirmed", "two_factor_enabled", "lockout_enabled", "access_failed_count", "preferred_language");
        await using var tokens = await Copy.OpenAsync(db, "identity.refresh_tokens",
            "expire_at_utc", "created_at_utc", "is_active", "user_id", "family_id", "token_hash");
        await using var devices = await Copy.OpenAsync(db, "identity.devices",
            "id", "installation_id", "user_id", "platform", "name", "push_token", "app_version", "registered_at", "last_seen_at", "session_id");
        await using var rules = await Copy.OpenAsync(db, "tasks.recurrence_rules", "id", "rrule", "time_zone_id", "start_date", "exceptions");
        await using var todos = await Copy.OpenAsync(db, "tasks.todos",
            "id", "title", "description", "recurrence_rule_id", "notification_enabled", "notification_lead_time", "user_id",
            "status", "created_at", "updated_at", "occurrences_generated_through");
        await using var occurrences = await Copy.OpenAsync(db, "tasks.todo_occurrences",
            "id", "todo_id", "scheduled_at", "original_scheduled_at", "status", "completed_at", "cancelled_at", "notify_at", "notified_at");
        await using var notifications = await Copy.OpenAsync(db, "notifications.notifications",
            "id", "user_id", "kind", "title", "body", "data_json", "created_at", "read_at");
        var budget = await BudgetCopies.OpenAsync(db);

        for (var i = 0; i < users; i++)
        {
            var userId = Guid.NewGuid().ToString();
            var zone = Pick(Zones);
            var since = now.AddDays(-rng.Next(historyDays, historyDays + 60));
            accounts.Row(userId, $"Load Tester {i}", zone, since, since, $"lt{i}", $"LT{i}", $"lt{i}@loadtest.invalid",
                $"LT{i}@LOADTEST.INVALID", true, passwordHash, Guid.NewGuid().ToString("N").ToUpperInvariant(),
                Guid.NewGuid().ToString(), false, false, true, 0, Pick(Languages));

            var session = Guid.NewGuid();
            tokens.Row(now.AddDays(30), now, true, userId, session, TokenHash($"lt-seed-{i}"));
            devices.Row(Guid.CreateVersion7(), Guid.NewGuid(), userId, "Android", "Pixel 8", $"lt-push-{i}", "1.0.0", since, now, session);
            if (rng.Next(10) == 0)
                devices.Row(Guid.CreateVersion7(), Guid.NewGuid(), userId, "Linux", "Desktop", null, "1.0.0", since, now, null);

            for (var t = rng.Next(2, 9); t > 0; t--)
                AddTodo(userId, zone, historyDays, horizon, rules, todos, occurrences);

            for (var n = 0; n < 30; n++)
            {
                var at = now.AddMinutes(-rng.Next(60, 30 * 24 * 60));
                var data = $$"""{"todoId": "{{Guid.NewGuid()}}", "occurrenceId": "{{Guid.NewGuid()}}", "scheduledAt": "{{at.AddMinutes(15):O}}", "pomodoroTemplateId": ""}""";
                notifications.Row(Guid.CreateVersion7(at), userId, "occurrence.due", Titles[rng.Next(Titles.Length)],
                    $"Starts at {at.AddMinutes(15):HH:mm} (in 15 min)", new Json(data), at, rng.Next(10) < 7 ? at.AddMinutes(rng.Next(1, 600)) : null);
            }

            budget.AddUser(userId, zone, now, rng, this);

            if ((i + 1) % 5000 == 0)
                Console.WriteLine($"  {i + 1} accounts, {todos.Rows} todos, {occurrences.Rows} occurrences");
        }

        Console.WriteLine($"accounts {accounts.Rows}, devices {devices.Rows}, todos {todos.Rows}, occurrences {occurrences.Rows}, " +
            $"notifications {notifications.Rows}, transactions {budget.Transactions.Rows}, recurring {budget.Recurring.Rows}");
        await budget.DisposeAsync();
    }

    private void AddTodo(string userId, string zone, int historyDays, DateTimeOffset horizon, Copy rules, Copy todos, Copy occurrences)
    {
        var pattern = Pick(Patterns);
        var hour = rng.Next(6, 22);
        var minute = rng.Next(10) < 7 ? 0 : 30; // people pick round times: natural peaks
        var tz = TimeZoneInfo.FindSystemTimeZoneById(zone);

        DateTimeOffset start;
        if (pattern == "once")
        {
            var day = TimeZoneInfo.ConvertTime(now, tz).Date.AddDays(rng.Next(1, 21));
            start = new DateTimeOffset(day.AddHours(hour).AddMinutes(minute), tz.GetUtcOffset(day.AddHours(hour)));
        }
        else
        {
            // A handful of start days, so the computed schedules can be shared between todos.
            var day = TimeZoneInfo.ConvertTime(now, tz).Date.AddDays(-historyDays + 15 * rng.Next(0, 5));
            start = new DateTimeOffset(day.AddHours(hour).AddMinutes(minute), tz.GetUtcOffset(day.AddHours(hour)));
        }

        var weekday = (DayOfWeek)rng.Next(0, 7);
        var key = pattern == "weekly" ? $"weekly-{weekday}-{(DayOfWeek)(((int)weekday + 3) % 7)}" : pattern;
        var (rrule, instants) = Schedule(key, zone, start.ToUniversalTime(), horizon);

        var ruleId = Guid.CreateVersion7(start);
        rules.Row(ruleId, rrule, zone, start.ToUniversalTime(), Array.Empty<DateTimeOffset>());

        var notify = rng.Next(10) < 6;
        var lead = TimeSpan.FromMinutes(Leads[rng.Next(Leads.Length)]);
        var todoId = Guid.CreateVersion7(start);
        var created = start.AddDays(-1) < now ? start.AddDays(-1) : now;
        todos.Row(todoId, Titles[rng.Next(Titles.Length)], "", ruleId, notify, lead, userId, "Scheduled", created, created,
            pattern == "once" ? DateTimeOffset.MaxValue : horizon);

        foreach (var at in instants)
        {
            DateTimeOffset? notifyAt = notify ? at - lead : null;
            if (at >= now)
            {
                occurrences.Row(Guid.CreateVersion7(at), todoId, at, at, "Pending", null, null, notifyAt, null);
                continue;
            }

            // The past: mostly done, some cancelled, some never touched (the reminder went out all the same).
            DateTimeOffset? notifiedAt = notifyAt is { } n && n < now ? n.AddSeconds(rng.Next(1, 6)) : null;
            var roll = rng.Next(100);
            if (roll < 65)
            {
                var done = at.AddMinutes(rng.Next(0, 120));
                occurrences.Row(Guid.CreateVersion7(at), todoId, at, at, "Completed", done < now ? done : now, null, notifyAt, notifiedAt);
            }
            else if (roll < 75)
                occurrences.Row(Guid.CreateVersion7(at), todoId, at, at, "Cancelled", null, at.AddMinutes(-rng.Next(0, 600)), notifyAt, notifiedAt);
            else
                occurrences.Row(Guid.CreateVersion7(at), todoId, at, at, "Pending", null, null, notifyAt, notifiedAt);
        }
    }

    /// <summary>The app's own engine, once per (rule, zone, start): every todo sharing them shares the instants.</summary>
    private (string Rrule, DateTimeOffset[] At) Schedule(string key, string zone, DateTimeOffset start, DateTimeOffset horizon)
    {
        if (schedules.TryGetValue((key, zone, start), out var cached))
            return cached;

        var spec = key switch
        {
            "daily" => new RecurrenceSpec(Frequency.Daily),
            "weekdays" => new RecurrenceSpec(Frequency.Weekly, ByDay: [DayOfWeek.Monday, DayOfWeek.Tuesday, DayOfWeek.Wednesday, DayOfWeek.Thursday, DayOfWeek.Friday]),
            "every-2-days" => new RecurrenceSpec(Frequency.Daily, Interval: 2),
            "monthly" => new RecurrenceSpec(Frequency.Monthly),
            "once" => new RecurrenceSpec(Frequency.Daily, Count: 1),
            _ when key.StartsWith("weekly-", StringComparison.Ordinal) =>
                new RecurrenceSpec(Frequency.Weekly, ByDay: [.. key.Split('-').Skip(1).Select(Enum.Parse<DayOfWeek>).Distinct()]),
            _ => throw new ArgumentException(key),
        };
        var schedule = RecurrenceSchedule.Create(spec, start, zone).Match<RecurrenceSchedule>(e => throw new InvalidOperationException(e.ErrorMessage), s => s);
        var result = (schedule.Rrule, schedule.GetOccurrences(start, horizon).ToArray());
        if (key != "once")
            schedules[(key, zone, start)] = result;
        return result;
    }

    /// <summary>A fresh session per account (<c>lt-{tag}-{i}</c>): one test run's refreshes rotate, the next run needs new ones.</summary>
    public async Task SessionsAsync(string tag)
    {
        var accounts = await AccountsAsync();
        await using (var tokens = await Copy.OpenAsync(db, "identity.refresh_tokens", "expire_at_utc", "created_at_utc", "is_active", "user_id", "family_id", "token_hash"))
        {
            foreach (var (index, userId) in accounts)
                tokens.Row(now.AddDays(30), now, true, userId, Guid.NewGuid(), TokenHash($"lt-{tag}-{index}"));
        }
        Console.WriteLine($"{accounts.Count} sessions tagged '{tag}'");
    }

    /// <summary>
    /// The peak: <paramref name="count"/> accounts each get a one-time todo whose reminder falls within the minute
    /// starting at <paramref name="at"/> (15 minutes ahead of it, notifications on).
    /// </summary>
    public async Task PeakAsync(DateTimeOffset at, int count)
    {
        at = at.ToUniversalTime();
        if (at < now.AddMinutes(2))
            throw new ArgumentException("The peak must be at least two minutes ahead: the reminder job would catch it piecemeal.");

        var accounts = (await AccountsAsync()).Take(count).ToList();
        await using (var rules = await Copy.OpenAsync(db, "tasks.recurrence_rules", "id", "rrule", "time_zone_id", "start_date", "exceptions"))
        await using (var todos = await Copy.OpenAsync(db, "tasks.todos",
            "id", "title", "description", "recurrence_rule_id", "notification_enabled", "notification_lead_time", "user_id",
            "status", "created_at", "updated_at", "occurrences_generated_through"))
        await using (var occurrences = await Copy.OpenAsync(db, "tasks.todo_occurrences",
            "id", "todo_id", "scheduled_at", "original_scheduled_at", "status", "notify_at"))
        {
            var lead = TimeSpan.FromMinutes(15);
            foreach (var (index, userId) in accounts)
            {
                var notifyAt = at.AddSeconds(index % 60);
                var due = notifyAt + lead;
                var schedule = RecurrenceSchedule.OneTime(due).Match<RecurrenceSchedule>(e => throw new InvalidOperationException(e.ErrorMessage), s => s);
                var ruleId = Guid.CreateVersion7();
                var todoId = Guid.CreateVersion7();
                rules.Row(ruleId, schedule.Rrule, schedule.TimeZoneId, schedule.Start, Array.Empty<DateTimeOffset>());
                todos.Row(todoId, "Peak reminder", "", ruleId, true, lead, userId, "Scheduled", now, now, DateTimeOffset.MaxValue);
                occurrences.Row(Guid.CreateVersion7(due), todoId, due, due, "Pending", notifyAt);
            }
        }
        Console.WriteLine($"peak: {accounts.Count} reminders due between {at:u} and {at.AddSeconds(59):u}");
    }

    public async Task AnalyzeAsync()
    {
        await using var analyze = db.CreateCommand("ANALYZE");
        analyze.CommandTimeout = 0;
        await analyze.ExecuteNonQueryAsync();
    }

    private async Task<List<(int Index, string UserId)>> AccountsAsync()
    {
        var accounts = new List<(int, string)>();
        await using var command = db.CreateCommand("SELECT id, user_name FROM identity.asp_net_users WHERE user_name LIKE 'lt%'");
        await using var reader = await command.ExecuteReaderAsync();
        while (await reader.ReadAsync())
        {
            if (int.TryParse(reader.GetString(1).AsSpan(2), out var index))
                accounts.Add((index, reader.GetString(0)));
        }
        accounts.Sort();
        return accounts;
    }

    /// <summary>As Identity stores a refresh token: uppercase hex SHA-256 of the raw token (JwtProvider.HashRefreshToken).</summary>
    internal static string TokenHash(string token) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(token)));

    internal T Pick<T>((T Value, int Weight)[] choices)
    {
        var roll = rng.Next(choices.Sum(c => c.Weight));
        foreach (var (value, weight) in choices)
        {
            if ((roll -= weight) < 0)
                return value;
        }
        return choices[^1].Value;
    }

}
