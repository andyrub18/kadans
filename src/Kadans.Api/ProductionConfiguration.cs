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

        if (string.Equals(configuration["Push:Provider"], "Fcm", StringComparison.OrdinalIgnoreCase)
            && string.IsNullOrWhiteSpace(configuration["Push:Firebase:CredentialsJson"])
            && string.IsNullOrWhiteSpace(configuration["Push:Firebase:CredentialsFile"]))
            problems.Add("Push:Provider is Fcm but neither Push:Firebase:CredentialsJson nor Push:Firebase:CredentialsFile is set.");

        return problems;
    }

    public static void ThrowIfIncomplete(IConfiguration configuration)
    {
        var problems = Problems(configuration);
        if (problems.Count > 0)
            throw new InvalidOperationException("Kadans cannot start – configuration is incomplete:\n - " + string.Join("\n - ", problems));
    }
}
