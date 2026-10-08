using System.Net;
using System.Text.Json;
using Kadans.SharedKernel.Http;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.Configuration;

namespace Kadans.Api.Tests;

/// <summary>The host's limiter, registered exactly as Program does, in an in-process test server.</summary>
public class RateLimitingTests
{
    private const string ClientHeader = "X-Test-Client";

    private static async Task<WebApplication> StartAsync(int emailPer15Minutes = 5, int credentialsPerMinute = 20, int requestsPerMinute = 300)
    {
        var builder = WebApplication.CreateBuilder();
        builder.WebHost.UseTestServer();
        builder.Configuration.AddInMemoryCollection(new Dictionary<string, string?>
        {
            ["RateLimiting:EmailPer15Minutes"] = $"{emailPer15Minutes}",
            ["RateLimiting:CredentialsPerMinute"] = $"{credentialsPerMinute}",
            ["RateLimiting:RequestsPerMinute"] = $"{requestsPerMinute}",
        });
        builder.Services.AddKadansRateLimiting(builder.Configuration);

        var app = builder.Build();
        // The test picks the client's address; in production the forwarded-headers middleware sets it from nginx.
        app.Use((http, next) =>
        {
            if (http.Request.Headers.TryGetValue(ClientHeader, out var client))
                http.Connection.RemoteIpAddress = IPAddress.Parse(client!);
            return next(http);
        });
        app.UseRateLimiter();
        app.MapPost("/mail", () => "sent").RequireRateLimiting(RateLimitPolicies.Email);
        app.MapPost("/sign-in", () => "ok").RequireRateLimiting(RateLimitPolicies.Credentials);
        app.MapGet("/anything", () => "ok");
        app.MapGet("/health/live", () => "ok");
        await app.StartAsync();
        return app;
    }

    private static async Task<HttpResponseMessage> SendAsync(WebApplication app, HttpMethod method, string path, string client = "203.0.113.7", string? language = null)
    {
        var request = new HttpRequestMessage(method, path);
        request.Headers.Add(ClientHeader, client);
        if (language is not null)
            request.Headers.Add("Accept-Language", language);
        return await app.GetTestClient().SendAsync(request);
    }

    [Test]
    public async Task The_sixth_mail_request_in_a_quarter_hour_is_refused_with_a_translated_429()
    {
        await using var app = await StartAsync(emailPer15Minutes: 5);
        for (var i = 0; i < 5; i++)
            await Assert.That((await SendAsync(app, HttpMethod.Post, "/mail")).StatusCode).IsEqualTo(HttpStatusCode.OK);

        var refused = await SendAsync(app, HttpMethod.Post, "/mail", language: "fr");

        await Assert.That(refused.StatusCode).IsEqualTo(HttpStatusCode.TooManyRequests);
        await Assert.That(refused.Headers.RetryAfter?.Delta).IsNotNull();
        using var problem = JsonDocument.Parse(await refused.Content.ReadAsStringAsync());
        await Assert.That(problem.RootElement.GetProperty("errorCode").GetString()).IsEqualTo("10053");
        await Assert.That(problem.RootElement.GetProperty("detail").GetString()).IsEqualTo("Trop de tentatives. Réessayez dans un moment.");
    }

    [Test]
    public async Task Credentials_allow_twenty_attempts_a_minute_per_client()
    {
        await using var app = await StartAsync(credentialsPerMinute: 20);
        for (var i = 0; i < 20; i++)
            await Assert.That((await SendAsync(app, HttpMethod.Post, "/sign-in")).StatusCode).IsEqualTo(HttpStatusCode.OK);

        await Assert.That((await SendAsync(app, HttpMethod.Post, "/sign-in")).StatusCode).IsEqualTo(HttpStatusCode.TooManyRequests);
        // Another client is not affected by the first one's attempts.
        await Assert.That((await SendAsync(app, HttpMethod.Post, "/sign-in", client: "198.51.100.20")).StatusCode).IsEqualTo(HttpStatusCode.OK);
    }

    [Test]
    public async Task Everything_else_shares_the_global_limit_but_health_checks_are_never_limited()
    {
        await using var app = await StartAsync(requestsPerMinute: 10);
        for (var i = 0; i < 10; i++)
            await Assert.That((await SendAsync(app, HttpMethod.Get, "/anything")).StatusCode).IsEqualTo(HttpStatusCode.OK);

        await Assert.That((await SendAsync(app, HttpMethod.Get, "/anything")).StatusCode).IsEqualTo(HttpStatusCode.TooManyRequests);
        for (var i = 0; i < 30; i++)
            await Assert.That((await SendAsync(app, HttpMethod.Get, "/health/live")).StatusCode).IsEqualTo(HttpStatusCode.OK);
    }

    [Test]
    public async Task An_ipv6_client_is_limited_by_its_64_prefix_not_by_each_address()
    {
        await using var app = await StartAsync(credentialsPerMinute: 2);
        await SendAsync(app, HttpMethod.Post, "/sign-in", client: "2001:db8:1:2::1");
        await SendAsync(app, HttpMethod.Post, "/sign-in", client: "2001:db8:1:2::2");

        // A third address in the same /64 shares the budget; the neighbouring /64 has its own.
        await Assert.That((await SendAsync(app, HttpMethod.Post, "/sign-in", client: "2001:db8:1:2:ffff::3")).StatusCode).IsEqualTo(HttpStatusCode.TooManyRequests);
        await Assert.That((await SendAsync(app, HttpMethod.Post, "/sign-in", client: "2001:db8:1:3::1")).StatusCode).IsEqualTo(HttpStatusCode.OK);
    }

    [Test]
    [Arguments("203.0.113.7", "203.0.113.7")]
    [Arguments("::ffff:203.0.113.7", "203.0.113.7")] // IPv4 seen through an IPv6 socket
    [Arguments("2001:db8:1:2:aaaa:bbbb:cccc:dddd", "2001:db8:1:2::/64")]
    public async Task The_partition_key_is_the_ipv4_address_or_the_ipv6_prefix(string address, string expected)
    {
        var http = new DefaultHttpContext { Connection = { RemoteIpAddress = IPAddress.Parse(address) } };

        await Assert.That(RateLimiting.ClientOf(http)).IsEqualTo(expected);
    }
}
