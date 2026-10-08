namespace Kadans.Api;

/// <summary>What the API cannot run without once it leaves a developer's machine.</summary>
internal static class ProductionConfiguration
{
    public static IReadOnlyList<string> Problems(IConfiguration configuration)
    {
        var problems = new List<string>();

        if (string.IsNullOrWhiteSpace(configuration.GetConnectionString("kadans")))
            problems.Add("ConnectionStrings:kadans is missing (env: ConnectionStrings__kadans).");

        var jwtKey = configuration["Jwt:Key"];
        if (string.IsNullOrWhiteSpace(jwtKey))
            problems.Add("Jwt:Key is missing (env: Jwt__Key) – at least 32 random characters.");
        else if (jwtKey.Length < 32)
            problems.Add("Jwt:Key is shorter than 32 characters.");

        foreach (var key in new[] { "Jwt:Issuer", "Jwt:Audience" })
        {
            if (string.IsNullOrWhiteSpace(configuration[key]))
                problems.Add($"{key} is missing.");
        }

        if (string.Equals(configuration["Email:Provider"], "Resend", StringComparison.OrdinalIgnoreCase)
            && string.IsNullOrWhiteSpace(configuration["Email:Resend:ApiKey"]))
            problems.Add("Email:Provider is Resend but Email:Resend:ApiKey is missing (env: Email__Resend__ApiKey).");

        if (!Uri.TryCreate(configuration["Email:LinkBaseUrl"], UriKind.Absolute, out var links) || links.Scheme != Uri.UriSchemeHttps)
            problems.Add("Email:LinkBaseUrl must be the public https URL of this API – emailed links open pages it serves.");

        // Pushes that only pretend to leave would silently cost every reminder on a phone.
        if (string.Equals(configuration["Push:Provider"], "Simulated", StringComparison.OrdinalIgnoreCase) && !configuration.GetValue<bool>("LoadTest:Enabled"))
            problems.Add("Push:Provider is Simulated, which sends nothing: it is for load tests only (LoadTest:Enabled, docs/LOADTEST.md).");

        if (string.Equals(configuration["Push:Provider"], "Fcm", StringComparison.OrdinalIgnoreCase)
            && string.IsNullOrWhiteSpace(configuration["Push:Firebase:CredentialsJson"]))
        {
            var file = configuration["Push:Firebase:CredentialsFile"];
            if (string.IsNullOrWhiteSpace(file))
                problems.Add("Push:Provider is Fcm but neither Push:Firebase:CredentialsJson nor Push:Firebase:CredentialsFile is set.");
            // Docker mounts a missing host file as an empty directory, so "set but absent" is the likely mistake.
            else if (!File.Exists(file))
                problems.Add($"Push:Firebase:CredentialsFile is {file}, which is not a file – with Docker Compose, put the key at deploy/secrets/firebase-admin.json before the first start.");
            // In the image the API runs as uid/gid 1654, not as the server's user who copied the key there.
            else if (!CanRead(file))
                problems.Add($"Push:Firebase:CredentialsFile {file} exists but this process cannot read it – on the server: sudo chown $USER:1654 deploy/secrets/firebase-admin.json && chmod 640 deploy/secrets/firebase-admin.json");
        }

        // Purchases without a store would give anyone a free subscription.
        if (configuration.GetValue<bool>("Billing:FakeStore:Enabled"))
            problems.Add("Billing:FakeStore:Enabled is on: it is for Development only (purchases without a store).");

        // Requiring subscriptions takes a way to sell them, and to hear from Google when they change.
        if (configuration.GetValue<bool>("Billing:Required"))
        {
            var keyFile = configuration["Billing:Google:ServiceAccountFile"];
            if (string.IsNullOrWhiteSpace(configuration["Billing:Google:ServiceAccountJson"]) && string.IsNullOrWhiteSpace(keyFile))
                problems.Add("Billing:Required is on but the Play Developer API key is missing (env: Billing__Google__ServiceAccountFile, with deploy/compose: PLAY_SERVICE_ACCOUNT_FILE).");
            else if (!string.IsNullOrWhiteSpace(keyFile) && !File.Exists(keyFile))
                problems.Add($"Billing:Google:ServiceAccountFile is {keyFile}, which is not a file – put the key in deploy/secrets/ (docs/DEPLOYMENT.md → Subscriptions).");
            else if (!string.IsNullOrWhiteSpace(keyFile) && !CanRead(keyFile))
                problems.Add($"Billing:Google:ServiceAccountFile {keyFile} exists but this process cannot read it – sudo chown $USER:1654 and chmod 640, like the Firebase key.");

            foreach (var (key, env) in new[]
            {
                ("Billing:Google:NotificationAudience", "Billing__Google__NotificationAudience"),
                ("Billing:Google:NotificationServiceAccount", "Billing__Google__NotificationServiceAccount"),
            })
            {
                if (string.IsNullOrWhiteSpace(configuration[key]))
                    problems.Add($"Billing:Required is on but {key} is missing (env: {env}).");
            }
        }

        return problems;
    }

    private static bool CanRead(string path)
    {
        try
        {
            using var _ = File.OpenRead(path);
            return true;
        }
        catch (Exception e) when (e is UnauthorizedAccessException or IOException)
        {
            return false;
        }
    }

    public static void ThrowIfIncomplete(IConfiguration configuration)
    {
        var problems = Problems(configuration);
        if (problems.Count > 0)
            throw new InvalidOperationException("Kadans cannot start – configuration is incomplete:\n - " + string.Join("\n - ", problems));
    }
}
