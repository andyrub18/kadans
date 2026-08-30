using Microsoft.Extensions.Options;

namespace Kadans.Modules.Identity.Security;

internal sealed class JwtParameter
{
    public const string SectionName = "Jwt";

    public required string Key { get; set; }
    public required string Issuer { get; set; }
    public required string Audience { get; set; }
    public int ExpirationInMinutes { get; set; }
    public int RefreshTokenExpirationInDays { get; set; } = 7;
    public int MfaChallengeExpirationInMinutes { get; set; } = 5;
}

internal sealed class JwtParameterOptionsSetup(IConfiguration configuration)
    : IConfigureOptions<JwtParameter>
{
    public void Configure(JwtParameter options) =>
        configuration.GetSection(JwtParameter.SectionName).Bind(options);
}
