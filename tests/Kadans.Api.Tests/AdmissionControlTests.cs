using System.Diagnostics.Metrics;
using System.Net;
using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Diagnostics.Metrics.Testing;

namespace Kadans.Api.Tests;

/// <summary>The host's load shedding, registered exactly as Program does, in an in-process test server.</summary>
public class AdmissionControlTests
{
    /// <summary>Holds every request to /slow until released, so a test decides when the server is full.</summary>
    private sealed class Gate
    {
        public TaskCompletionSource Entered { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public TaskCompletionSource Release { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
    }

    private static async Task<(WebApplication App, Gate Gate)> StartAsync(int places, int queue, int queueTimeoutMilliseconds = 10_000)
    {
        var builder = WebApplication.CreateBuilder();
        builder.WebHost.UseTestServer();
        builder.Configuration.AddInMemoryCollection(new Dictionary<string, string?>
        {
            ["Admission:MaxConcurrentRequests"] = $"{places}",
            ["Admission:QueueLimit"] = $"{queue}",
            ["Admission:QueueTimeoutMilliseconds"] = $"{queueTimeoutMilliseconds}",
            ["Admission:RetryAfterSeconds"] = "5",
        });
        builder.Services.AddKadansAdmissionControl(builder.Configuration);

        var gate = new Gate();
        var app = builder.Build();
        app.UseKadansAdmissionControl();
        app.MapGet("/slow", async () =>
        {
            gate.Entered.TrySetResult();
            await gate.Release.Task;
            return "done";
        });
        app.MapGet("/fast", () => "ok");
        app.MapGet("/health/live", () => "ok");
        app.MapPost("/hubs/kadans/negotiate", () => "ok");
        await app.StartAsync();
        return (app, gate);
    }

    private static Task<HttpResponseMessage> SendAsync(WebApplication app, string path, HttpMethod? method = null, string? language = null)
    {
        var request = new HttpRequestMessage(method ?? HttpMethod.Get, path);
        if (language is not null)
            request.Headers.Add("Accept-Language", language);
        return app.GetTestClient().SendAsync(request);
    }

    /// <summary>The only place is taken: a request is in /slow and stays there until the gate opens.</summary>
    private static async Task<Task<HttpResponseMessage>> FillAsync(WebApplication app, Gate gate)
    {
        var holding = SendAsync(app, "/slow");
        await gate.Entered.Task.WaitAsync(TimeSpan.FromSeconds(10));
        return holding;
    }

    private static async Task WaitingAsync(WebApplication app, long count)
    {
        var admission = app.Services.GetRequiredService<AdmissionControl>();
        for (var i = 0; i < 500 && admission.Waiting != count; i++)
            await Task.Delay(10);
        await Assert.That(admission.Waiting).IsEqualTo(count);
    }

    [Test]
    public async Task Past_the_queue_a_request_is_turned_away_at_once_with_a_translated_503()
    {
        var (app, gate) = await StartAsync(places: 1, queue: 0);
        await using var _ = app;
        var holding = await FillAsync(app, gate);

        var refused = await SendAsync(app, "/fast", language: "fr");

        await Assert.That(refused.StatusCode).IsEqualTo(HttpStatusCode.ServiceUnavailable);
        await Assert.That(refused.Headers.RetryAfter?.Delta).IsEqualTo(TimeSpan.FromSeconds(5));
        using var problem = JsonDocument.Parse(await refused.Content.ReadAsStringAsync());
        await Assert.That(problem.RootElement.GetProperty("errorCode").GetString()).IsEqualTo("10058");
        await Assert.That(problem.RootElement.GetProperty("detail").GetString()).IsEqualTo("Le serveur est surchargé. Réessayez dans un moment.");

        gate.Release.SetResult();
        await Assert.That((await holding).StatusCode).IsEqualTo(HttpStatusCode.OK);
        await Assert.That((await SendAsync(app, "/fast")).StatusCode).IsEqualTo(HttpStatusCode.OK);
    }

    [Test]
    public async Task A_request_waits_for_a_place_and_is_worked_on_when_one_frees()
    {
        var (app, gate) = await StartAsync(places: 1, queue: 1);
        await using var _ = app;
        using var waits = new MetricCollector<double>(app.Services.GetRequiredService<IMeterFactory>(), AdmissionMetrics.MeterName, "kadans.admission.wait");
        var holding = await FillAsync(app, gate);

        var waiting = SendAsync(app, "/fast");
        await WaitingAsync(app, 1);
        gate.Release.SetResult();

        await Assert.That((await waiting).StatusCode).IsEqualTo(HttpStatusCode.OK);
        await Assert.That((await holding).StatusCode).IsEqualTo(HttpStatusCode.OK);
        await Assert.That(waits.GetMeasurementSnapshot().Count).IsEqualTo(1);
    }

    [Test]
    public async Task A_full_queue_or_too_long_a_wait_turns_a_request_away_and_says_which()
    {
        var (app, gate) = await StartAsync(places: 1, queue: 1, queueTimeoutMilliseconds: 1000);
        await using var _ = app;
        using var shed = new MetricCollector<long>(app.Services.GetRequiredService<IMeterFactory>(), AdmissionMetrics.MeterName, "kadans.admission.shed");
        var holding = await FillAsync(app, gate);

        var waiting = SendAsync(app, "/fast");
        await WaitingAsync(app, 1);
        var beyondTheQueue = await SendAsync(app, "/fast");
        var waitedTooLong = await waiting;

        await Assert.That(beyondTheQueue.StatusCode).IsEqualTo(HttpStatusCode.ServiceUnavailable);
        await Assert.That(waitedTooLong.StatusCode).IsEqualTo(HttpStatusCode.ServiceUnavailable);
        var reasons = shed.GetMeasurementSnapshot().Select(m => m.Tags["reason"]).ToArray();
        await Assert.That(reasons).IsEquivalentTo(new object?[] { "queue_full", "queue_timeout" });

        gate.Release.SetResult();
        await holding;
    }

    [Test]
    public async Task A_request_turned_away_is_marked_in_the_request_metrics()
    {
        var (app, gate) = await StartAsync(places: 1, queue: 0);
        await using var _ = app;
        using var requests = new MetricCollector<double>(app.Services.GetRequiredService<IMeterFactory>(), "Microsoft.AspNetCore.Hosting", "http.server.request.duration");
        var holding = await FillAsync(app, gate);

        await SendAsync(app, "/fast");
        gate.Release.SetResult();
        await holding;
        await requests.WaitForMeasurementsAsync(2, TimeSpan.FromSeconds(10));

        // The dashboard's and the alert's "Server errors" filter on this label (kadans_admission!="shed").
        var measurements = requests.GetMeasurementSnapshot();
        var shed = measurements.Single(m => Equals(m.Tags["http.response.status_code"], 503));
        await Assert.That(shed.Tags["kadans.admission"]).IsEqualTo("shed");
        var served = measurements.Single(m => Equals(m.Tags["http.response.status_code"], 200));
        await Assert.That(served.Tags.ContainsKey("kadans.admission")).IsFalse();
    }

    [Test]
    public async Task Health_checks_and_hub_connections_take_no_place()
    {
        var (app, gate) = await StartAsync(places: 1, queue: 0);
        await using var _ = app;
        var holding = await FillAsync(app, gate);

        await Assert.That((await SendAsync(app, "/fast")).StatusCode).IsEqualTo(HttpStatusCode.ServiceUnavailable);
        await Assert.That((await SendAsync(app, "/health/live")).StatusCode).IsEqualTo(HttpStatusCode.OK);
        // Every place is taken but nobody waits for one: the server keeps up, a new app may connect.
        await Assert.That((await SendAsync(app, "/hubs/kadans/negotiate", HttpMethod.Post)).StatusCode).IsEqualTo(HttpStatusCode.OK);

        gate.Release.SetResult();
        await holding;
    }

    [Test]
    public async Task A_new_hub_connection_is_turned_away_while_requests_wait_for_a_place()
    {
        var (app, gate) = await StartAsync(places: 1, queue: 1);
        await using var _ = app;
        var holding = await FillAsync(app, gate);
        var waiting = SendAsync(app, "/fast");
        await WaitingAsync(app, 1);

        await Assert.That((await SendAsync(app, "/hubs/kadans/negotiate", HttpMethod.Post)).StatusCode).IsEqualTo(HttpStatusCode.ServiceUnavailable);

        gate.Release.SetResult();
        await Assert.That((await waiting).StatusCode).IsEqualTo(HttpStatusCode.OK);
        await holding;
        await Assert.That((await SendAsync(app, "/hubs/kadans/negotiate", HttpMethod.Post)).StatusCode).IsEqualTo(HttpStatusCode.OK);
    }
}
