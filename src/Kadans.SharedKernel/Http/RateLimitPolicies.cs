namespace Kadans.SharedKernel.Http;

/// <summary>
/// The host's per-client rate-limit policies, by name: modules put them on endpoints with
/// <c>RequireRateLimiting</c>, the host defines their numbers (configuration section <c>RateLimiting</c>). Every
/// other request falls under the host's global per-client limit.
/// </summary>
public static class RateLimitPolicies
{
    /// <summary>Endpoints that send an email to an address the caller chooses (register, forgot password…).</summary>
    public const string Email = "email";

    /// <summary>Endpoints that check a password or a code, where guessing is the risk.</summary>
    public const string Credentials = "credentials";
}
