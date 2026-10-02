using System.Security.Claims;
using Kadans.Modules.Notifications.Realtime;
using Kadans.SharedKernel.Security;
using Microsoft.AspNetCore.Http.Features;
using Microsoft.AspNetCore.SignalR;
using Microsoft.Extensions.Logging.Abstractions;

namespace Kadans.Notifications.Tests;

public class HubConnectionsTests
{
    private sealed class Connection(string connectionId, string? sessionId) : HubCallerContext
    {
        public bool Aborted { get; private set; }

        public override string ConnectionId => connectionId;
        public override string? UserIdentifier => "alice";
        public override ClaimsPrincipal? User { get; } =
            new(new ClaimsIdentity(sessionId is null ? [] : [new Claim(SessionClaim.Type, sessionId)], "Bearer"));
        public override IDictionary<object, object?> Items { get; } = new Dictionary<object, object?>();
        public override IFeatureCollection Features { get; } = new FeatureCollection();
        public override CancellationToken ConnectionAborted => CancellationToken.None;

        public override void Abort() => Aborted = true;
    }

    [Test]
    public async Task An_ended_session_loses_its_live_connections_and_only_those()
    {
        var connections = new HubConnections(NullLogger<HubConnections>.Instance);
        var phone = new Connection("c1", "session-phone");
        var phoneSecondTab = new Connection("c2", "session-phone");
        var laptop = new Connection("c3", "session-laptop");
        var beforeSessions = new Connection("c4", null); // opened with a token from before sessions were stamped
        foreach (var connection in new[] { phone, phoneSecondTab, laptop, beforeSessions })
            connections.Opened(connection);

        connections.SessionsEnded("alice", ["session-phone"]);

        await Assert.That(phone.Aborted).IsTrue();
        await Assert.That(phoneSecondTab.Aborted).IsTrue();
        await Assert.That(laptop.Aborted).IsFalse();
        await Assert.That(beforeSessions.Aborted).IsFalse();
        await Assert.That(connections.Count).IsEqualTo(2);
    }

    [Test]
    public async Task A_closed_connection_is_forgotten()
    {
        var connections = new HubConnections(NullLogger<HubConnections>.Instance);
        var phone = new Connection("c1", "session-phone");
        connections.Opened(phone);

        connections.Closed("c1");
        connections.SessionsEnded("alice", ["session-phone"]);

        await Assert.That(phone.Aborted).IsFalse();
        await Assert.That(connections.Count).IsEqualTo(0);
    }
}
