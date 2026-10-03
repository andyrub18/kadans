using System.Linq.Expressions;
using Microsoft.EntityFrameworkCore;

namespace Kadans.SharedKernel.Persistence;

/// <summary>
/// What every module's nightly cleanup shares (ARCHITECTURE → Data retention). Rows that outlived their use go a
/// batch at a time, so a large backlog never holds a long lock or one huge transaction, and a run has a ceiling: what
/// is left goes the next night.
/// </summary>
public static class Retention
{
    /// <summary>07:30 UTC: 03:30 in Port-au-Prince in summer, 02:30 in winter. Quartz cron, seconds first.</summary>
    public const string NightlyCron = "0 30 7 * * ?";

    public const int BatchSize = 5_000;
    public const int MaxBatchesPerRun = 1_000;

    /// <summary>
    /// Deletes what <paramref name="due"/> returns, oldest keys first, until none is left; returns how many went. Each
    /// batch reads its keys, then deletes those rows if they still qualify.
    /// </summary>
    public static async Task<int> DeleteInBatchesAsync<T, TKey>(
        IQueryable<T> due,
        Expression<Func<T, TKey>> key,
        CancellationToken cancellationToken
    )
        where T : class
    {
        var total = 0;
        for (var batch = 0; batch < MaxBatchesPerRun; batch++)
        {
            var keys = await due.OrderBy(key).Select(key).Take(BatchSize).ToListAsync(cancellationToken);
            if (keys.Count == 0)
                break;

            total += await due.Where(KeyIn(key, keys)).ExecuteDeleteAsync(cancellationToken);
            if (keys.Count < BatchSize)
                break;
        }

        return total;
    }

    /// <summary><c>row => keys.Contains(key(row))</c>.</summary>
    private static Expression<Func<T, bool>> KeyIn<T, TKey>(Expression<Func<T, TKey>> key, List<TKey> keys) =>
        Expression.Lambda<Func<T, bool>>(
            Expression.Call(typeof(Enumerable), nameof(Enumerable.Contains), [typeof(TKey)], Expression.Constant(keys), key.Body),
            key.Parameters
        );
}
