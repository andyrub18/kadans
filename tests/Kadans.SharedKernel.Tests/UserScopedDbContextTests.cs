using Kadans.SharedKernel.Persistence;
using Kadans.SharedKernel.Security;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace Kadans.SharedKernel.Tests;

/// <summary>
/// Pooled contexts outlive their request: whoever rents one must see their own rows, never the previous renter's.
/// </summary>
public class UserScopedDbContextTests
{
    private sealed class Note
    {
        public int Id { get; set; }
        public string UserId { get; set; } = "";
    }

    private sealed class NotesContext(DbContextOptions<NotesContext> options) : UserScopedDbContext(options)
    {
        public DbSet<Note> Notes => Set<Note>();

        protected override void OnModelCreating(ModelBuilder builder) =>
            builder.Entity<Note>().HasQueryFilter(note => note.UserId == CurrentUserId);
    }

    /// <summary>The request's caller, as the host's <see cref="CurrentUserService"/> reads it from the token.</summary>
    private sealed class Caller : ICurrentUserService
    {
        public string? UserId { get; set; }
        public string? SessionId => null;
    }

    private static async Task<(ServiceProvider Services, SqliteConnection Connection)> StartAsync()
    {
        var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var services = new ServiceCollection()
            .AddScoped<Caller>()
            .AddScoped<ICurrentUserService>(provider => provider.GetRequiredService<Caller>())
            .AddUserScopedDbContextPool<NotesContext>(options => options.UseSqlite(connection))
            .BuildServiceProvider(validateScopes: true);

        await using var scope = services.CreateAsyncScope();
        var context = scope.ServiceProvider.GetRequiredService<NotesContext>();
        await context.Database.EnsureCreatedAsync();
        context.Notes.AddRange(new Note { UserId = "alice" }, new Note { UserId = "alice" }, new Note { UserId = "bob" });
        await context.SaveChangesAsync();
        return (services, connection);
    }

    private static async Task<(NotesContext Context, int Seen)> AsAsync(ServiceProvider services, string user)
    {
        await using var scope = services.CreateAsyncScope();
        scope.ServiceProvider.GetRequiredService<Caller>().UserId = user;
        var context = scope.ServiceProvider.GetRequiredService<NotesContext>();
        var seen = await context.Notes.CountAsync();
        await Assert.That(await context.Notes.AllAsync(note => note.UserId == user)).IsTrue();
        return (context, seen);
    }

    [Test]
    public async Task Each_request_sees_its_own_rows_through_the_same_pooled_context()
    {
        var (services, connection) = await StartAsync();
        await using var _ = services;
        await using var __ = connection;

        var (first, alices) = await AsAsync(services, "alice");
        var (second, bobs) = await AsAsync(services, "bob");

        await Assert.That(ReferenceEquals(first, second)).IsTrue(); // the pool handed the same instance back
        await Assert.That(alices).IsEqualTo(2);
        await Assert.That(bobs).IsEqualTo(1);
    }

    private static readonly Func<NotesContext, Task<int>> CompiledCount = EF.CompileAsyncQuery((NotesContext db) => db.Notes.Count());

    [Test]
    public async Task A_compiled_query_applies_the_filter_of_the_context_that_runs_it()
    {
        var (services, connection) = await StartAsync();
        await using var _ = services;
        await using var __ = connection;

        async Task<int> CountAs(string user)
        {
            await using var scope = services.CreateAsyncScope();
            scope.ServiceProvider.GetRequiredService<Caller>().UserId = user;
            return await CompiledCount(scope.ServiceProvider.GetRequiredService<NotesContext>());
        }

        await Assert.That(await CountAs("alice")).IsEqualTo(2);
        await Assert.That(await CountAs("bob")).IsEqualTo(1);
        await Assert.That(await CountAs("carol")).IsEqualTo(0);
    }

    [Test]
    public async Task A_context_rented_without_a_caller_sees_no_ones_rows_not_the_last_renters()
    {
        var (services, connection) = await StartAsync();
        await using var _ = services;
        await using var __ = connection;
        var (rentedByAlice, _) = await AsAsync(services, "alice");

        // Straight from the pool, as a job or a background service could: nobody was handed to it.
        await using var unscoped = services.GetRequiredService<IDbContextFactory<NotesContext>>().CreateDbContext();

        await Assert.That(ReferenceEquals(unscoped, rentedByAlice)).IsTrue();
        await Assert.That(await unscoped.Notes.CountAsync()).IsEqualTo(0);
        await Assert.That(await unscoped.Notes.IgnoreQueryFilters().CountAsync()).IsEqualTo(3);
    }
}
