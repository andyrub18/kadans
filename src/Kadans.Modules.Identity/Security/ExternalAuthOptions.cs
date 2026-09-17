namespace Kadans.Modules.Identity.Security;

internal sealed class ExternalAuthOptions
{
    public const string SectionName = "ExternalAuth";

    public GoogleProviderOptions Google { get; set; } = new();
    public ExternalProviderOptions Apple { get; set; } = new();

    public class ExternalProviderOptions
    {
        /// <summary>
        /// Audiences an ID token may carry: OAuth client ids (Google) or the app bundle id /
        /// services id (Apple). One per client platform.
        /// </summary>
        public List<string> ClientIds { get; set; } = [];

        /// <summary>Every audience the validator accepts for this provider.</summary>
        public virtual IReadOnlyList<string> Audiences() => Clean(ClientIds);

        protected static IReadOnlyList<string> Clean(IEnumerable<string?> ids) =>
            [.. ids.Where(id => !string.IsNullOrWhiteSpace(id)).Select(id => id!.Trim()).Distinct(StringComparer.Ordinal)];
    }

    /// <summary>
    /// Google needs one OAuth client per way of signing in. Naming them here (instead of only
    /// listing audiences) lets the API tell the clients which id to use (<c>GET /auth/providers</c>)
    /// and do the desktop code exchange itself, so the desktop client secret never ships in the app.
    /// </summary>
    public sealed class GoogleProviderOptions : ExternalProviderOptions
    {
        /// <summary>"Desktop app" OAuth client: the JVM app's loopback sign-in.</summary>
        public DesktopClientOptions Desktop { get; set; } = new();

        /// <summary>
        /// "Web application" OAuth client: Android's Credential Manager asks for ID tokens with
        /// this id as <c>serverClientId</c>, so it is the audience of every Android sign-in.
        /// </summary>
        public string? WebClientId { get; set; }

        public override IReadOnlyList<string> Audiences() => Clean([.. ClientIds, Desktop.ClientId, WebClientId]);
    }

    public sealed class DesktopClientOptions
    {
        public string? ClientId { get; set; }
        public string? ClientSecret { get; set; }

        public bool IsConfigured => !string.IsNullOrWhiteSpace(ClientId) && !string.IsNullOrWhiteSpace(ClientSecret);
    }
}
