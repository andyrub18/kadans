using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Security.Cryptography;
using Kadans.Api.Data;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.Tokens;
using Kadans.Api.Security;

namespace Kadans.Api.Security;

public sealed class JwtProvider(
    IOptions<JwtParameter> options,
    ApplicationDbContext dbContext,
    ILogger<JwtProvider> logger
)
{
    private readonly JwtParameter parameter = options.Value;

    public async Task<string> CreateToken(ApplicationUser user)
    {
        var roleNames = await dbContext
            .UserRoles.Where(ur => ur.UserId == user.Id)
            .Join(dbContext.Roles, ur => ur.RoleId, r => r.Id, (_, r) => r.Name)
            .ToListAsync();

        if (roleNames is null)
        {
            logger.LogWarning("No roles found for user {UserId}", user.Id);
            roleNames = [];
        }

        List<Claim> claims =
        [
            new Claim(ClaimTypes.NameIdentifier, user.Id),
            new Claim(ClaimTypes.Name, user.UserName ?? string.Empty),
            .. roleNames
                .Where(rn => !string.IsNullOrEmpty(rn))
                .Select(rn => new Claim(ClaimTypes.Role, rn!)),
        ];

        var keyBytes = System.Text.Encoding.UTF8.GetBytes(parameter.Key);
        var signingKey = new SymmetricSecurityKey(keyBytes);
        var credentials = new SigningCredentials(signingKey, SecurityAlgorithms.HmacSha256);

        var token = new JwtSecurityToken(
            issuer: parameter.Issuer,
            audience: parameter.Audience,
            claims: claims,
            expires: DateTimeOffset.UtcNow.AddMinutes(parameter.ExpirationInMinutes).UtcDateTime,
            signingCredentials: credentials
        );

        return new JwtSecurityTokenHandler().WriteToken(token);
    }

    public static string GenerateRefreshToken()
    {
        var bytes = RandomNumberGenerator.GetBytes(64);
        return Convert.ToBase64String(bytes);
    }
}
