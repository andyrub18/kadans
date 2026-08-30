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

public static class ModelConfigurationBuilderExtensions
{
    public static ModelConfigurationBuilder StoreDateTimeOffsetsAsUtc(
        this ModelConfigurationBuilder builder
    )
    {
        builder.Properties<DateTimeOffset>().HaveConversion<UtcDateTimeOffsetConverter>();
        return builder;
    }
}
