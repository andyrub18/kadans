using Kadans.SharedKernel.Http;
using Kadans.SharedKernel.Security;

namespace Kadans.SharedKernel.Tests;

/// <summary>Addresses that are their own credential (the calendar feed's link): never written whole, signed with a key of their own.</summary>
public class SecretLinksTests
{
    [Test]
    [Arguments("/calendar/feed/THE-SECRET/kadans.ics")]
    [Arguments("/calendar/feed/THE-SECRET")]
    [Arguments("/calendar/feed/THE-SECRET/anything/else")]
    [Arguments("/Calendar/FEED/THE-SECRET/kadans.ics")] // routes match whatever the case
    public async Task A_secret_links_path_is_written_up_to_its_prefix(string path)
    {
        await Assert.That(SecretLinks.Holds(path)).IsTrue();
        await Assert.That(SecretLinks.Loggable(path)).IsEqualTo("/calendar/feed/…");
    }

    [Test]
    [Arguments("/todos/42")]
    [Arguments("/calendar-feed")] // Settings' side: signed in, no secret in it
    [Arguments("/calendar-feed/reset")]
    [Arguments("/calendar/feeds")]
    [Arguments("/")]
    public async Task Any_other_path_is_written_whole(string path)
    {
        await Assert.That(SecretLinks.Holds(path)).IsFalse();
        await Assert.That(SecretLinks.Loggable(path)).IsEqualTo(path);
    }

    [Test]
    [Arguments("/calendar/feed/THE-SECRET/kadans.ics", "/calendar/feed/…")]
    [Arguments("https://api.kadansplanning.com/calendar/feed/THE-SECRET/kadans.ics?x=THE-SECRET", "https://api.kadansplanning.com/calendar/feed/…")]
    [Arguments("Request starting HTTP/1.1 GET http://localhost/Calendar/Feed/THE-SECRET - - -", "Request starting HTTP/1.1 GET http://localhost/calendar/feed/…")]
    public async Task A_text_quoting_a_secret_link_is_cut_after_its_prefix(string text, string expected)
    {
        await Assert.That(SecretLinks.Hide(text)).IsEqualTo(expected);
    }

    [Test]
    [Arguments("/todos/42")]
    [Arguments("/calendar-feed/reset")]
    [Arguments("/calendar/feed")] // the prefix alone names no link
    [Arguments("")]
    public async Task A_text_without_one_is_left_alone(string text)
    {
        await Assert.That(SecretLinks.Hide(text)).IsNull();
    }

    [Test]
    public async Task Each_purpose_has_its_own_key_from_the_servers_secret()
    {
        var feed = ServerSecret.DeriveKey("the-servers-secret-0123456789-0123456789", "Kadans.CalendarFeed.Link");

        await Assert.That(feed.Length).IsEqualTo(32);
        await Assert.That(ServerSecret.DeriveKey("the-servers-secret-0123456789-0123456789", "Kadans.CalendarFeed.Link")).IsEquivalentTo(feed);
        await Assert.That(ServerSecret.DeriveKey("the-servers-secret-0123456789-0123456789", "Kadans.Something.Else").SequenceEqual(feed)).IsFalse();
        await Assert.That(ServerSecret.DeriveKey("another-servers-secret-9876543210-987654", "Kadans.CalendarFeed.Link").SequenceEqual(feed)).IsFalse();
    }

    [Test]
    public async Task Without_the_servers_secret_no_key_is_made()
    {
        var empty = new Microsoft.Extensions.Configuration.ConfigurationBuilder().Build();

        await Assert.That(() => ServerSecret.DeriveKey(empty, "Kadans.CalendarFeed.Link")).Throws<InvalidOperationException>();
    }
}
