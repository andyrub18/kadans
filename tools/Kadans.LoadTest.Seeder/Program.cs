using System.Diagnostics;
using Kadans.LoadTest.Seeder;
using Npgsql;

// Fills a throwaway Kadans database for the load test (docs/LOADTEST.md):
//
//   seed     --connection "<cs>" [--users 50000] [--history-days 75] [--peak-at <ISO instant>] [--peak-count 20000]
//   sessions --connection "<cs>" --tag <name>      a fresh session per account (k6 resumes them; a refresh rotates them)
//   peak     --connection "<cs>" --at <ISO instant> [--count 20000]   that many reminders due in that minute
//
// The database's name must contain "loadtest": the seeder refuses anything else, production above all.
var command = args.FirstOrDefault();
var options = Options.Parse(args.Skip(1));
if (command is not ("seed" or "sessions" or "peak") || options.Get("connection") is not { } connection)
{
    Console.Error.WriteLine("usage: seed|sessions|peak --connection \"<connection string>\" [options] (docs/LOADTEST.md)");
    return 2;
}

var builder = new NpgsqlConnectionStringBuilder(connection);
if (builder.Database is not { } database || !database.Contains("loadtest", StringComparison.OrdinalIgnoreCase))
{
    Console.Error.WriteLine($"Refusing database '{builder.Database}': its name must contain \"loadtest\".");
    return 2;
}

await using var dataSource = NpgsqlDataSource.Create(builder.ConnectionString);
var seeder = new Seeder(dataSource, options.Int("seed", 42));
var watch = Stopwatch.StartNew();

switch (command)
{
    case "seed":
        await seeder.SeedAsync(
            users: options.Int("users", 50_000),
            historyDays: options.Int("history-days", 75),
            password: options.Get("password") ?? Seeder.DefaultPassword
        );
        if (options.Get("peak-at") is { } peakAt)
            await seeder.PeakAsync(DateTimeOffset.Parse(peakAt), options.Int("peak-count", 20_000));
        break;
    case "sessions":
        await seeder.SessionsAsync(options.Get("tag") ?? throw new ArgumentException("--tag is required"));
        break;
    case "peak":
        await seeder.PeakAsync(DateTimeOffset.Parse(options.Get("at") ?? throw new ArgumentException("--at is required")), options.Int("count", 20_000));
        break;
}

await seeder.AnalyzeAsync();
Console.WriteLine($"done in {watch.Elapsed:mm\\:ss}");
return 0;

internal sealed class Options
{
    private readonly Dictionary<string, string> values = new(StringComparer.OrdinalIgnoreCase);

    public static Options Parse(IEnumerable<string> args)
    {
        var options = new Options();
        string? key = null;
        foreach (var arg in args)
        {
            if (arg.StartsWith("--", StringComparison.Ordinal))
                key = arg[2..];
            else if (key is not null)
            {
                options.values[key] = arg;
                key = null;
            }
        }
        return options;
    }

    public string? Get(string key) => values.GetValueOrDefault(key);

    public int Int(string key, int fallback) => values.TryGetValue(key, out var v) ? int.Parse(v) : fallback;
}
