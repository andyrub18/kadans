using Kadans.Modules.Tasks.Domain;
using Kadans.Modules.Tasks.Features.CalendarFeed;
using Kadans.SharedKernel.Security;

namespace Kadans.Tasks.Tests;

/// <summary>The secret in a calendar feed's link (ARCHITECTURE → "The calendar feed"): written by this server only, read back by it alone.</summary>
public class CalendarFeedLinksTests
{
    private static CalendarFeedLinks Links(string serverKey = "a-development-key-of-at-least-32-characters") =>
        new(ServerSecret.DeriveKey(serverKey, CalendarFeedLinks.KeyPurpose));

    private static byte[] Nonce() => new CalendarFeedLink { UserId = "marie" }.Nonce;

    [Test]
    public async Task A_link_reads_back_to_its_nonce()
    {
        var links = Links();
        var nonce = Nonce();

        var secret = links.SecretOf(nonce);

        await Assert.That(links.TryRead(secret, out var read)).IsTrue();
        await Assert.That(read).IsEquivalentTo(nonce);
    }

    [Test]
    public async Task A_secret_is_64_characters_a_url_never_escapes()
    {
        var secret = Links().SecretOf(Nonce());

        await Assert.That(secret.Length).IsEqualTo(64);
        await Assert.That(secret.All(c => char.IsAsciiLetterOrDigit(c) || c is '-' or '_')).IsTrue();
    }

    [Test]
    public async Task The_path_is_under_the_secret_links_prefix_and_ends_as_a_calendar_file()
    {
        var links = Links();
        var nonce = Nonce();

        await Assert.That(links.PathOf(nonce)).IsEqualTo($"/calendar/feed/{links.SecretOf(nonce)}/kadans.ics");
    }

    [Test]
    public async Task The_same_link_is_written_again_whenever_asked()
    {
        var links = Links();
        var nonce = Nonce();

        await Assert.That(links.SecretOf(nonce)).IsEqualTo(links.SecretOf(nonce));
        await Assert.That(links.SecretOf(Nonce())).IsNotEqualTo(links.SecretOf(nonce));
    }

    [Test]
    public async Task A_secret_changed_anywhere_is_refused()
    {
        var links = Links();
        var secret = links.SecretOf(Nonce());

        for (var i = 0; i < secret.Length; i++)
        {
            var changed = secret[..i] + (secret[i] == 'A' ? 'B' : 'A') + secret[(i + 1)..];
            await Assert.That(links.TryRead(changed, out _)).IsFalse();
        }
    }

    [Test]
    public async Task A_nonce_from_the_database_is_not_a_link()
    {
        // What a database dump holds: the nonce, without the signature only the server's key writes.
        var links = Links();
        var nonce = Nonce();
        var unsigned = System.Buffers.Text.Base64Url.EncodeToString([.. nonce, .. new byte[32]]);

        await Assert.That(unsigned.Length).IsEqualTo(64);
        await Assert.That(links.TryRead(unsigned, out _)).IsFalse();
    }

    [Test]
    public async Task A_link_signed_under_another_server_key_is_refused()
    {
        var secret = Links("the-key-before-it-was-changed-0123456789").SecretOf(Nonce());

        await Assert.That(Links("the-key-after-it-was-changed-9876543210").TryRead(secret, out _)).IsFalse();
    }

    [Test]
    [Arguments(null)]
    [Arguments("")]
    [Arguments("kadans")]
    [Arguments("../../etc/passwd")]
    [Arguments("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")] // 64 characters, not base64url
    [Arguments("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")] // one too many
    public async Task Anything_else_is_refused(string? secret)
    {
        await Assert.That(Links().TryRead(secret, out var nonce)).IsFalse();
        await Assert.That(nonce).IsEmpty();
    }
}
