namespace Kadans.Modules.Notifications.Push;

internal sealed class PushOptions
{
    public const string SectionName = "Push";

    /// <summary>"Fcm" sends through Firebase Cloud Messaging; anything else logs the push (development).</summary>
    public string Provider { get; set; } = "Log";

    public FirebaseOptions Firebase { get; set; } = new();

    public sealed class FirebaseOptions
    {
        /// <summary>Service-account JSON, as one secret value.</summary>
        public string? CredentialsJson { get; set; }

        /// <summary>Alternative to <see cref="CredentialsJson"/>: path to the service-account file.</summary>
        public string? CredentialsFile { get; set; }
    }
}
