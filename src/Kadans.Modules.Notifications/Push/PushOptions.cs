namespace Kadans.Modules.Notifications.Push;

internal sealed class PushOptions
{
    public const string SectionName = "Push";

    /// <summary>
    /// "Fcm" sends through Firebase Cloud Messaging; "Simulated" only pretends to, with Firebase's timing (load tests,
    /// refused elsewhere); anything else logs the push (development).
    /// </summary>
    public string Provider { get; set; } = "Log";

    public FirebaseOptions Firebase { get; set; } = new();

    public SimulatedOptions Simulated { get; set; } = new();

    public sealed class SimulatedOptions
    {
        /// <summary>How long one call to the provider takes (up to 500 messages each, like Firebase's SendEach).</summary>
        public int LatencyMilliseconds { get; set; } = 150;
    }

    public sealed class FirebaseOptions
    {
        /// <summary>Service-account JSON, as one secret value.</summary>
        public string? CredentialsJson { get; set; }

        /// <summary>Alternative to <see cref="CredentialsJson"/>: path to the service-account file.</summary>
        public string? CredentialsFile { get; set; }
    }
}
