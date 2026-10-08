using Npgsql;

namespace Kadans.SharedKernel.Persistence;

/// <summary>
/// The connection string every module's DbContext shares (one pool), with the driver settings the load test showed
/// matter (docs/LOADTEST.md). Each applies only when the connection string does not set it already.
/// <list type="bullet">
/// <item>Statements prepared on the server once used twice: planning was two thirds of a typical query's time.</item>
/// <item>At most 40 connections: two cores cannot run 100 queries at once, and the 100 of the default only added
/// contention and memory under load; past 40, a query waits for a connection instead.</item>
/// </list>
/// </summary>
public static class KadansDatabase
{
    public const string ConnectionStringName = "kadans";
    public const int MaxPoolSize = 40;
    public const int MaxAutoPrepare = 50;

    public static string? ConnectionString(IConfiguration configuration)
    {
        var configured = configuration.GetConnectionString(ConnectionStringName);
        return string.IsNullOrWhiteSpace(configured) ? configured : WithDefaults(configured);
    }

    public static string WithDefaults(string connectionString)
    {
        // Npgsql's builder reports every keyword it knows as present: look at what the string itself sets.
        var set = new System.Data.Common.DbConnectionStringBuilder { ConnectionString = connectionString }.Keys
            .Cast<string>()
            .Select(k => k.Replace(" ", "", StringComparison.Ordinal).ToLowerInvariant())
            .ToHashSet();
        var builder = new NpgsqlConnectionStringBuilder(connectionString);
        if (!set.Contains("maxautoprepare"))
            builder.MaxAutoPrepare = MaxAutoPrepare;
        if (!set.Contains("autoprepareminusages"))
            builder.AutoPrepareMinUsages = 2;
        if (!set.Overlaps(["maxpoolsize", "maximumpoolsize"]))
            builder.MaxPoolSize = MaxPoolSize;
        return builder.ConnectionString;
    }
}
