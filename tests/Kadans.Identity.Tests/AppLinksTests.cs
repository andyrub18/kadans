using System.Text.Json;
using System.Text.RegularExpressions;
using Kadans.Modules.Identity.Features.Account;
using Kadans.Modules.Identity.Features.Auth;

namespace Kadans.Identity.Tests;

public class AppLinksTests
{
    [Test]
    public async Task The_file_is_what_Android_reads_with_the_Play_signing_key_alone()
    {
        using var file = JsonDocument.Parse(AppLinks.AssetLinksJson);
        var statement = file.RootElement.EnumerateArray().Single();
        var target = statement.GetProperty("target");
        var fingerprints = target.GetProperty("sha256_cert_fingerprints").EnumerateArray().Select(f => f.GetString()!).ToList();

        await Assert.That(statement.GetProperty("relation")[0].GetString()).IsEqualTo("delegate_permission/common.handle_all_urls");
        await Assert.That(target.GetProperty("namespace").GetString()).IsEqualTo("android_app");
        await Assert.That(target.GetProperty("package_name").GetString()).IsEqualTo("app.kadans");
        await Assert.That(fingerprints.Count).IsEqualTo(1);
        await Assert.That(Regex.IsMatch(fingerprints[0], "^([0-9A-F]{2}:){31}[0-9A-F]{2}$")).IsTrue();
    }

    [Test]
    public async Task The_reset_page_never_hands_its_token_to_a_custom_scheme()
    {
        var page = AuthPages.ResetPassword(EmailTexts.For("en"), "a@b.ht", "the-token");

        await Assert.That(page).DoesNotContain("kadans://");
    }
}
