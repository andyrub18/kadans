using Humanizer;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata;

namespace Kadans.SharedKernel.Persistence;

public static class ModelBuilderExtensions
{
    /// <summary>
    /// Rewrites table, column, key and foreign-key names to snake_case so the schema
    /// follows Postgres conventions regardless of the C# names.
    /// </summary>
    public static ModelBuilder UseSnakeCaseNames(this ModelBuilder builder)
    {
        foreach (var entity in builder.Model.GetEntityTypes())
        {
            entity.SetTableName(entity.GetTableName()?.Underscore());

            var objectIdentifier = StoreObjectIdentifier.Table(
                entity.GetTableName()?.Underscore()!,
                entity.GetSchema()
            );

            foreach (var property in entity.GetProperties())
                property.SetColumnName(property.GetColumnName(objectIdentifier)?.Underscore());

            foreach (var key in entity.GetKeys())
                key.SetName(key.GetName()?.Underscore());

            foreach (var key in entity.GetForeignKeys())
                key.SetConstraintName(key.GetConstraintName()?.Underscore());
        }

        return builder;
    }
}
