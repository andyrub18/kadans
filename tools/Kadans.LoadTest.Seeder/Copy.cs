using Npgsql;
using NpgsqlTypes;

namespace Kadans.LoadTest.Seeder;

/// <summary>A jsonb value for <see cref="Copy"/>.</summary>
internal sealed record Json(string Value);

/// <summary>
/// One table's binary COPY on its own connection, so every table streams at once. Foreign keys are not checked
/// (<c>session_replication_role = replica</c>; the seeder writes consistent rows itself), so the order does not matter.
/// </summary>
internal sealed class Copy : IAsyncDisposable
{
    private readonly NpgsqlConnection connection;
    private readonly NpgsqlBinaryImporter importer;

    private Copy(string table, NpgsqlConnection connection, NpgsqlBinaryImporter importer)
    {
        Table = table;
        this.connection = connection;
        this.importer = importer;
    }

    public string Table { get; }

    public long Rows { get; private set; }

    public static async Task<Copy> OpenAsync(NpgsqlDataSource db, string table, params string[] columns)
    {
        var connection = await db.OpenConnectionAsync();
        await using (var command = new NpgsqlCommand("SET session_replication_role = replica", connection))
            await command.ExecuteNonQueryAsync();
        var quoted = string.Join(", ", columns.Select(c => $"\"{c}\""));
        var importer = await connection.BeginBinaryImportAsync($"COPY {table} ({quoted}) FROM STDIN (FORMAT BINARY)");
        return new Copy(table, connection, importer);
    }

    public void Row(params object?[] values)
    {
        importer.StartRow();
        foreach (var value in values)
        {
            switch (value)
            {
                case null:
                    importer.WriteNull();
                    break;
                case string s:
                    importer.Write(s, NpgsqlDbType.Text);
                    break;
                case Guid g:
                    importer.Write(g, NpgsqlDbType.Uuid);
                    break;
                case DateTimeOffset d:
                    importer.Write(d.ToUniversalTime(), NpgsqlDbType.TimestampTz);
                    break;
                case bool b:
                    importer.Write(b, NpgsqlDbType.Boolean);
                    break;
                case int i:
                    importer.Write(i, NpgsqlDbType.Integer);
                    break;
                case decimal m:
                    importer.Write(m, NpgsqlDbType.Numeric);
                    break;
                case TimeSpan t:
                    importer.Write(t, NpgsqlDbType.Interval);
                    break;
                case Json j:
                    importer.Write(j.Value, NpgsqlDbType.Jsonb);
                    break;
                case DateTimeOffset[] a:
                    importer.Write(a, NpgsqlDbType.Array | NpgsqlDbType.TimestampTz);
                    break;
                default:
                    throw new ArgumentException($"No COPY mapping for {value.GetType()}");
            }
        }
        Rows++;
    }

    public async ValueTask DisposeAsync()
    {
        await importer.CompleteAsync();
        await importer.DisposeAsync();
        await connection.DisposeAsync();
    }
}
