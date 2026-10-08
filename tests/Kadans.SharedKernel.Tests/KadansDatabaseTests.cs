using Kadans.SharedKernel.Persistence;
using Npgsql;

namespace Kadans.SharedKernel.Tests;

public class KadansDatabaseTests
{
    [Test]
    public async Task Statements_are_prepared_and_the_pool_capped_unless_the_connection_string_says_otherwise()
    {
        var defaults = new NpgsqlConnectionStringBuilder(KadansDatabase.WithDefaults("Host=db;Database=kadans;Username=kadans;Password=x"));
        await Assert.That(defaults.MaxAutoPrepare).IsEqualTo(50);
        await Assert.That(defaults.AutoPrepareMinUsages).IsEqualTo(2);
        await Assert.That(defaults.MaxPoolSize).IsEqualTo(40);

        // Set in the connection string: kept.
        var explicitOnes = new NpgsqlConnectionStringBuilder(KadansDatabase.WithDefaults("Host=db;Maximum Pool Size=10;Max Auto Prepare=0"));
        await Assert.That(explicitOnes.MaxPoolSize).IsEqualTo(10);
        await Assert.That(explicitOnes.MaxAutoPrepare).IsEqualTo(0);
    }
}
