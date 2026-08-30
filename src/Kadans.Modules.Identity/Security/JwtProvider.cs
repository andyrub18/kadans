using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Security.Cryptography;
using System.Text;
using Kadans.Modules.Identity.Domain;
using Kadans.Modules.Identity.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.Tokens;

namespace Kadans.Modules.Identity.Security;

internal sealed class JwtProvider(IOptions<JwtParameter> options, IdentityModuleDbContext dbContext)
{
    private const string PurposeClaim = "purpose";
    private const string MfaPurpose = "mfa";

    // Challenge tokens carry their own audience so the API's bearer handler can never accept one.
    private string MfaAudience => $"{parameter.Audience}:mfa";

    private readonly JwtParameter parameter = options.Value;

    public async Task<string> CreateToken(ApplicationUser user)
    {
        var roleNames = await dbContext
            .UserRoles.Where(ur => ur.UserId == user.Id)
            .Join(dbContext.Roles, ur => ur.RoleId, r => r.Id, (_, r) => r.Name)
            .ToListAsync();

        List<Claim> claims =
        [
            new Claim(ClaimTypes.NameIdentifier, user.Id),
            new Claim(ClaimTypes.Name, user.UserName ?? string.Empty),
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
    public string? ValidateMfaChallengeToken(string token)
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
                    ValidAudience = MfaAudience,
                    IssuerSigningKey = SigningKey,
                    ClockSkew = TimeSpan.FromSeconds(30),
                },
                out _
            );

            if (principal.FindFirstValue(PurposeClaim) != MfaPurpose)
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
