using Kadans.SharedKernel.Http;

namespace Kadans.SharedKernel.Tests;

public class PagingTests
{
    [Test]
    public async Task Pages_count_from_one_and_hold_at_most_a_hundred()
    {
        await Assert.That(Paging.Check(1, 1)).IsNull();
        await Assert.That(Paging.Check(7, Paging.MaxPageSize)).IsNull();

        await Assert.That(Paging.Check(0, 20)!.Errors.Select(e => e.Code)).IsEquivalentTo(["InvalidPage"]);
        await Assert.That(Paging.Check(1, 0)!.Errors.Select(e => e.Code)).IsEquivalentTo(["InvalidPageSize"]);
        await Assert.That(Paging.Check(-3, 5_000)!.Errors.Select(e => e.Code)).IsEquivalentTo(["InvalidPage", "InvalidPageSize"]);
    }
}
