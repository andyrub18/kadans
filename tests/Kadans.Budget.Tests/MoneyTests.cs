using Kadans.Modules.Budget.Domain;

namespace Kadans.Budget.Tests;

public class MoneyTests
{
    [Test]
    public async Task Same_currency_arithmetic_works()
    {
        var total = new Money(1500.50m, Currency.Htg) + new Money(499.50m, Currency.Htg);
        await Assert.That(total).IsEqualTo(new Money(2000.00m, Currency.Htg));
        await Assert.That((total - new Money(500m, Currency.Htg)).Amount).IsEqualTo(1500.00m);
    }

    [Test]
    public async Task Mixing_gourdes_and_dollars_throws()
    {
        // The bi-monetary rule: never combine implicitly — no hidden exchange rate.
        await Assert.That(() => new Money(100m, Currency.Htg) + new Money(1m, Currency.Usd))
            .Throws<InvalidOperationException>();
    }

    [Test]
    public async Task Amount_validation_guards_the_gates()
    {
        await Assert.That(Money.ValidateAmount(250.75m).IsT1).IsTrue();
        await Assert.That(Money.ValidateAmount(0m).IsT0).IsTrue();
        await Assert.That(Money.ValidateAmount(-5m).IsT0).IsTrue();
        await Assert.That(Money.ValidateAmount(1.999m).IsT0).IsTrue(); // three decimals
        await Assert.That(Money.ValidateAmount(2_000_000_000_000m).IsT0).IsTrue();
    }
}
