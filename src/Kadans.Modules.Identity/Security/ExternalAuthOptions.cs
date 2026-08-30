namespace Kadans.Modules.Identity.Security;

internal sealed class ExternalAuthOptions
{
    public const string SectionName = "ExternalAuth";

    public ExternalProviderOptions Google { get; set; } = new();
    public ExternalProviderOptions Apple { get; set; } = new();

    public sealed class ExternalProviderOptions
    {
        /// <summary>
        /// Audiences an ID token may carry: OAuth client ids (Google) or the app bundle id /
        /// services id (Apple). One per client platform.
        /// </summary>
        public List<string> ClientIds { get; set; } = [];
    }
}
