using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Security.Cryptography;
using System.Text;
using Kadans.Modules.Identity.Domain;
using Kadans.Modules.Identity.Persistence;
using Kadans.SharedKernel.Security;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.Tokens;

namespace Kadans.Modules.Identity.Security;

internal sealed class JwtProvider(IOptions<JwtParameter> options, IdentityModuleDbContext dbContext)
{
    private const string PurposeClaim = "purpose";
    private const string MfaPurpose = "mfa";
    private const string RestorePurpose = "restore";

    // Challenge tokens carry their own audience so the API's bearer handler can never accept one.
    private string MfaAudience => $"{parameter.Audience}:mfa";
    private string RestoreAudience => $"{parameter.Audience}:restore";

    /// <summary>How long a "keep my account" offer stands after a sign-in into an account awaiting erasure.</summary>
    internal static readonly TimeSpan RestoreTokenLifetime = TimeSpan.FromMinutes(10);

    private readonly JwtParameter parameter = options.Value;

    /// <param name="sessionId">The refresh-token family the token belongs to (<see cref="SessionClaim"/>).</param>
    public async Task<string> CreateToken(ApplicationUser user, Guid sessionId)
    {
        var roleNames = await dbContext
            .UserRoles.Where(ur => ur.UserId == user.Id)
            .Join(dbContext.Roles, ur => ur.RoleId, r => r.Id, (_, r) => r.Name)
            .ToListAsync();

        List<Claim> claims =
        [
            new Claim(ClaimTypes.NameIdentifier, user.Id),
            new Claim(ClaimTypes.Name, user.UserName ?? string.Empty),
            new Claim(SessionClaim.Type, sessionId.ToString()),
            .. roleNames
                .Where(rn => !string.IsNullOrEmpty(rn))
                .Select(rn => new Claim(ClaimTypes.Role, rn!)),
        ];

        return Write(claims, TimeSpan.FromMinutes(parameter.ExpirationInMinutes), parameter.Audience);
    }

    /// <summary>
    /// Short-lived token proving the password step of a login succeeded; exchanged together
    /// with a TOTP code for real tokens. It carries no roles and is rejected by the API's
    /// bearer authentication because of its purpose claim.
    /// </summary>
    public string CreateMfaChallengeToken(ApplicationUser user) =>
        Write(
            [new Claim(ClaimTypes.NameIdentifier, user.Id), new Claim(PurposeClaim, MfaPurpose)],
            TimeSpan.FromMinutes(parameter.MfaChallengeExpirationInMinutes),
            MfaAudience
        );

    /// <summary>Returns the user id carried by a valid MFA challenge token, or null.</summary>
    public string? ValidateMfaChallengeToken(string token) => ValidatePurposeToken(token, MfaAudience, MfaPurpose);

    /// <summary>
    /// A sign-in into an account awaiting erasure proved who this is, and hands out this instead of a session: it can
    /// only keep the account (<c>POST /auth/restore-account</c>). Its own audience, so no API endpoint accepts it.
    /// </summary>
    public string CreateRestoreToken(ApplicationUser user) =>
        Write(
            [new Claim(ClaimTypes.NameIdentifier, user.Id), new Claim(PurposeClaim, RestorePurpose)],
            RestoreTokenLifetime,
            RestoreAudience
        );

    /// <summary>Returns the user id carried by a valid restore token, or null.</summary>
    public string? ValidateRestoreToken(string token) => ValidatePurposeToken(token, RestoreAudience, RestorePurpose);

    private string? ValidatePurposeToken(string token, string audience, string purpose)
    {
        try
        {
            var principal = new JwtSecurityTokenHandler().ValidateToken(
                token,
                new TokenValidationParameters
                {
                    ValidateIssuer = true,
                    ValidateAudience = true,
                    ValidateLifetime = true,
                    ValidateIssuerSigningKey = true,
                    ValidIssuer = parameter.Issuer,
                    ValidAudience = audience,
                    IssuerSigningKey = SigningKey,
                    ClockSkew = TimeSpan.FromSeconds(30),
                },
                out _
            );

            if (principal.FindFirstValue(PurposeClaim) != purpose)
                return null;

            return principal.FindFirstValue(ClaimTypes.NameIdentifier);
        }
        catch (SecurityTokenException)
        {
            return null;
        }
        catch (ArgumentException)
        {
            return null;
        }
    }

    public static string GenerateRefreshToken() =>
        Convert.ToBase64String(RandomNumberGenerator.GetBytes(64));

    public static string HashRefreshToken(string token) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(token)));

    private SymmetricSecurityKey SigningKey => new(Encoding.UTF8.GetBytes(parameter.Key));

    private string Write(IEnumerable<Claim> claims, TimeSpan lifetime, string audience)
    {
        var token = new JwtSecurityToken(
            issuer: parameter.Issuer,
            audience: audience,
            claims: claims,
            expires: DateTimeOffset.UtcNow.Add(lifetime).UtcDateTime,
            signingCredentials: new SigningCredentials(SigningKey, SecurityAlgorithms.HmacSha256)
        );

        return new JwtSecurityTokenHandler().WriteToken(token);
    }
}
