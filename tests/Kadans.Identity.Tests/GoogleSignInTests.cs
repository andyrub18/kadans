using System.Net;
using Kadans.Modules.Identity.Security;
using Kadans.SharedKernel.Errors;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;

namespace Kadans.Identity.Tests;

public class GoogleSignInTests
{
    private sealed class StubHandler(HttpStatusCode status, string body) : HttpMessageHandler
    {
        public HttpRequestMessage? Request { get; private set; }
        public string? Form { get; private set; }

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Request = request;
            Form = request.Content is null ? null : await request.Content.ReadAsStringAsync(cancellationToken);
            return new HttpResponseMessage(status) { Content = new StringContent(body) };
        }
    }

    private static ExternalAuthOptions Configured() =>
        new()
        {
            Google =
            {
                ClientIds = ["ios-id", " "],
                Desktop = { ClientId = "desktop-id", ClientSecret = "desktop-secret" },
                WebClientId = "web-id",
            },
        };

    private static GoogleCodeExchange Exchange(ExternalAuthOptions options, StubHandler handler) =>
        new(new HttpClient(handler), Options.Create(options), NullLogger<GoogleCodeExchange>.Instance);

    [Test]
    public async Task Audiences_are_the_listed_ids_plus_the_named_desktop_and_web_clients()
    {
        var audiences = Configured().Google.Audiences();

        await Assert.That(audiences).IsEquivalentTo(["ios-id", "desktop-id", "web-id"]);
    }

    [Test]
    public async Task Nothing_configured_means_no_audience_at_all()
    {
        await Assert.That(new ExternalAuthOptions().Google.Audiences().Count).IsEqualTo(0);
    }

    [Test]
    public async Task Exchange_sends_code_verifier_and_secret_and_returns_the_id_token()
    {
        var handler = new StubHandler(HttpStatusCode.OK, """{"access_token":"a","id_token":"the.id.token"}""");

        var result = await Exchange(Configured(), handler).ExchangeAsync("code-1", "verifier-1", "http://127.0.0.1:50123", CancellationToken.None);

        await Assert.That(result.IsT1).IsTrue();
        await Assert.That(result.AsT1).IsEqualTo("the.id.token");
        await Assert.That(handler.Request!.RequestUri!.ToString()).IsEqualTo(GoogleCodeExchange.TokenEndpoint);
        await Assert.That(handler.Form!).Contains("code=code-1");
        await Assert.That(handler.Form!).Contains("code_verifier=verifier-1");
        await Assert.That(handler.Form!).Contains("client_id=desktop-id");
        await Assert.That(handler.Form!).Contains("client_secret=desktop-secret");
        await Assert.That(handler.Form!).Contains("grant_type=authorization_code");
    }

    [Test]
    public async Task Google_refusing_the_code_is_a_failed_external_login()
    {
        var handler = new StubHandler(HttpStatusCode.BadRequest, """{"error":"invalid_grant"}""");

        var result = await Exchange(Configured(), handler).ExchangeAsync("stale", "v", "http://localhost:50123", CancellationToken.None);

        await Assert.That(result.AsT0.ErrorType).IsEqualTo(ErrorTypes.ExternalLoginFailed);
    }

    [Test]
    public async Task A_response_without_id_token_is_a_failed_external_login()
    {
        var handler = new StubHandler(HttpStatusCode.OK, """{"access_token":"a"}""");

        var result = await Exchange(Configured(), handler).ExchangeAsync("code", "v", "http://127.0.0.1:1", CancellationToken.None);

        await Assert.That(result.AsT0.ErrorType).IsEqualTo(ErrorTypes.ExternalLoginFailed);
    }

    [Test]
    public async Task Without_a_desktop_client_the_provider_is_not_configured_and_google_is_never_called()
    {
        var handler = new StubHandler(HttpStatusCode.OK, "{}");

        var result = await Exchange(new ExternalAuthOptions(), handler).ExchangeAsync("code", "v", "http://127.0.0.1:1", CancellationToken.None);

        await Assert.That(result.AsT0.ErrorType).IsEqualTo(ErrorTypes.ExternalProviderNotConfigured);
        await Assert.That(handler.Request).IsNull();
    }

    [Test]
    [Arguments("https://evil.example/cb")]
    [Arguments("https://127.0.0.1:50123")]
    [Arguments("kadans://auth")]
    [Arguments("")]
    public async Task Only_loopback_http_redirects_are_exchanged(string redirectUri)
    {
        var handler = new StubHandler(HttpStatusCode.OK, """{"id_token":"x"}""");

        var result = await Exchange(Configured(), handler).ExchangeAsync("code", "v", redirectUri, CancellationToken.None);

        await Assert.That(result.AsT0.ErrorType).IsEqualTo(ErrorTypes.ExternalLoginFailed);
        await Assert.That(handler.Request).IsNull();
    }
}
