using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Storage.ValueConversion;

namespace Kadans.SharedKernel.Persistence;

/// <summary>
/// Npgsql only accepts <see cref="DateTimeOffset"/> values with a zero offset for
/// <c>timestamp with time zone</c>. Clients legitimately send local offsets, so every
/// DateTimeOffset is normalized to UTC on the way to the database.
/// </summary>
public sealed class UtcDateTimeOffsetConverter()
    : ValueConverter<DateTimeOffset, DateTimeOffset>(v => v.ToUniversalTime(), v => v);

/// <summary>
/// SQLite (the in-process tests) stores a <see cref="DateTimeOffset"/> as text it cannot compare or sort. As UTC ticks
/// it can, so a test runs the same date filters production runs on Postgres.
/// </summary>
public sealed class UtcTicksDateTimeOffsetConverter()
    : ValueConverter<DateTimeOffset, long>(v => v.UtcTicks, v => new DateTimeOffset(v, TimeSpan.Zero));

public static class ModelConfigurationBuilderExtensions
{
    /// <param name="providerName">The context's <c>Database.ProviderName</c>: SQLite gets sortable ticks.</param>
    public static ModelConfigurationBuilder StoreDateTimeOffsetsAsUtc(
        this ModelConfigurationBuilder builder,
        string? providerName = null
    )
    {
        if (providerName == "Microsoft.EntityFrameworkCore.Sqlite")
            builder.Properties<DateTimeOffset>().HaveConversion<UtcTicksDateTimeOffsetConverter>();
        else
            builder.Properties<DateTimeOffset>().HaveConversion<UtcDateTimeOffsetConverter>();
        return builder;
    }
}
