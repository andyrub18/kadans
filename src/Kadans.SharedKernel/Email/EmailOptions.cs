namespace Kadans.SharedKernel.Email;

public sealed class EmailOptions
{
    public const string SectionName = "Email";

    /// <summary>"Resend" sends real mail; anything else logs the message instead (development).</summary>
    public string Provider { get; set; } = "Log";

    /// <summary>Sender, e.g. <c>Kadans &lt;no-reply@kadans.app&gt;</c>. Must be a Resend-verified domain in production.</summary>
    public string From { get; set; } = "Kadans <no-reply@kadans.local>";

    /// <summary>Base URL used to build links in emails (confirm, reset). Points at whatever handles the deep link.</summary>
    public string LinkBaseUrl { get; set; } = "http://localhost:5199";

    public ResendOptions Resend { get; set; } = new();

    public sealed class ResendOptions
    {
        public string ApiKey { get; set; } = string.Empty;
    }
}
