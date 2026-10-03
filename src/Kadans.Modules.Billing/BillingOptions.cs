namespace Kadans.Modules.Billing;

internal sealed class BillingOptions
{
    public const string SectionName = "Billing";

    /// <summary>
    /// Whether phones need a subscription. Off until the store product is live: then the apps show no paywall and
    /// every phone gets its reminders. Turned on in configuration, never in code.
    /// </summary>
    public bool Required { get; set; }

    public GooglePlayOptions Google { get; set; } = new();

    public FakeStoreOptions FakeStore { get; set; } = new();
}

internal sealed class GooglePlayOptions
{
    /// <summary>The app's package name in Play Console.</summary>
    public string PackageName { get; set; } = "app.kadans";

    /// <summary>The subscription's product id in Play Console (Monetize → Subscriptions).</summary>
    public string ProductId { get; set; } = "kadans_mobile";

    /// <summary>The free-trial offer's id, to tell a trial from a paid period.</summary>
    public string TrialOfferId { get; set; } = "trial-14d";

    /// <summary>The service account key (JSON) with access to the Play Developer API. A secret; or <see cref="ServiceAccountFile"/>.</summary>
    public string? ServiceAccountJson { get; set; }

    /// <summary>The same key as a file (production mounts <c>deploy/secrets/</c>, like the Firebase key).</summary>
    public string? ServiceAccountFile { get; set; }

    /// <summary>The push endpoint's URL, as the Pub/Sub push subscription's OIDC audience.</summary>
    public string? NotificationAudience { get; set; }

    /// <summary>The service account Pub/Sub signs its pushes as (the push subscription's authentication).</summary>
    public string? NotificationServiceAccount { get; set; }

    public bool IsConfigured => !string.IsNullOrWhiteSpace(ServiceAccountJson) || !string.IsNullOrWhiteSpace(ServiceAccountFile);
}

internal sealed class FakeStoreOptions
{
    /// <summary>Development only (the production guard refuses it): purchases without a store, to try the flows.</summary>
    public bool Enabled { get; set; }
}
