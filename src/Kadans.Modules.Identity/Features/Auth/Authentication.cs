using Kadans.Modules.Identity.Contracts;
using Kadans.Modules.Identity.Domain;
using Kadans.Modules.Identity.Persistence;
using Kadans.Modules.Identity.Security;
using Kadans.SharedKernel.Errors;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using OneOf;
using OneOf.Types;

namespace Kadans.Modules.Identity.Features.Auth;

internal sealed class Authentication(
    ILogger<Authentication> logger,
    JwtProvider jwtProvider,
    UserManager<ApplicationUser> userManager,
    SignInManager<ApplicationUser> signInManager,
    IdentityModuleDbContext dbContext,
    Sessions sessions,
    IOptions<JwtParameter> jwtParameterOptions
)
{
    private readonly JwtParameter jwtParameter = jwtParameterOptions.Value;

    /// <summary>Why a token replaced by a refresh is inactive; presenting one again means someone copied it.</summary>
    private const string RotatedReason = "rotated";

    public async Task<OneOf<ApplicationError, LoginResponse>> Login(LoginRequest request)
    {
        var user =
            await userManager.FindByNameAsync(request.Username)
            ?? (request.Username.Contains('@') ? await userManager.FindByEmailAsync(request.Username) : null);

        if (user is null)
        {
            logger.LogWarning("Login failed: unknown user {Username}", request.Username);
            return new ApplicationError(ErrorTypes.InvalidCredentials, "Invalid username or password");
        }

        if (await userManager.IsLockedOutAsync(user))
        {
            logger.LogWarning("Locked-out user {UserId} attempted to sign in", user.Id);
            return new ApplicationError(ErrorTypes.UserInactive, "User is deactivated");
        }

        var signInResult = await signInManager.CheckPasswordSignInAsync(user, request.Password, lockoutOnFailure: true);

        if (signInResult.IsLockedOut)
        {
            logger.LogWarning("User {UserId} is locked out", user.Id);
            return new ApplicationError(ErrorTypes.UserInactive, "User is deactivated");
        }

        if (!signInResult.Succeeded)
        {
            logger.LogWarning("Login failed: invalid password for user {UserId}", user.Id);
            return new ApplicationError(ErrorTypes.InvalidCredentials, "Invalid username or password");
        }

        return await IssueTokensOrChallengeAsync(user);
    }

    public async Task<OneOf<ApplicationError, LoginResponse>> VerifyMfa(MfaVerifyRequest request)
    {
        var userId = jwtProvider.ValidateMfaChallengeToken(request.MfaToken);
        if (userId is null)
            return new ApplicationError(ErrorTypes.InvalidToken, "The MFA token is invalid or expired.");

        var user = await userManager.FindByIdAsync(userId);
        if (user is null || await userManager.IsLockedOutAsync(user))
            return new ApplicationError(ErrorTypes.UserInactive, "User is deactivated");

        if (!await VerifyAuthenticatorOrRecoveryCodeAsync(user, request.Code))
        {
            await userManager.AccessFailedAsync(user);
            logger.LogWarning("Invalid MFA code for user {UserId}", user.Id);
            return new ApplicationError(ErrorTypes.MfaCodeInvalid, "The verification code is not valid.");
        }

        await userManager.ResetAccessFailedCountAsync(user);
        return await IssueTokensAsync(user);
    }

    public async Task<OneOf<ApplicationError, LoginResponse>> RefreshToken(RefreshTokenRequest request)
    {
        var hash = JwtProvider.HashRefreshToken(request.RefreshToken);
        var token = await dbContext.RefreshTokens.Include(rt => rt.User).FirstOrDefaultAsync(rt => rt.TokenHash == hash);

        if (token is null)
        {
            logger.LogWarning("Refresh failed: unknown token");
            return new ApplicationError(ErrorTypes.InvalidCredentials, "Invalid refresh token");
        }

        if (!token.IsActive)
        {
            // Signed out, or ended some other way: nothing more to do.
            if (token.RevokedReason != RotatedReason)
                return new ApplicationError(ErrorTypes.InvalidCredentials, "Invalid refresh token");

            // An already-rotated token is being replayed: someone else holds a copy of this session.
            logger.LogWarning(
                "Refresh token reuse detected for user {UserId}, family {FamilyId}; revoking family",
                token.UserId,
                token.FamilyId
            );
            await sessions.EndAsync(token.UserId, token.FamilyId, "reuse detected");
            return new ApplicationError(ErrorTypes.InvalidCredentials, "Invalid refresh token");
        }

        if (token.IsExpired)
        {
            token.Revoke("expired");
            await dbContext.SaveChangesAsync();
            return new ApplicationError(ErrorTypes.InvalidCredentials, "Invalid refresh token");
        }

        if (await userManager.IsLockedOutAsync(token.User))
        {
            await sessions.EndAsync(token.UserId, token.FamilyId, "user deactivated");
            return new ApplicationError(ErrorTypes.UserInactive, "User is deactivated");
        }

        token.Revoke(RotatedReason);
        return await IssueTokensAsync(token.User, token.FamilyId);
    }

    /// <summary>Signs this session out: its tokens, its access from the next request on, and the device it registered.</summary>
    public async Task RevokeRefreshToken(string refreshToken)
    {
        var hash = JwtProvider.HashRefreshToken(refreshToken);
        var token = await dbContext.RefreshTokens.FirstOrDefaultAsync(rt => rt.TokenHash == hash);
        if (token is null)
        {
            logger.LogWarning("Attempted to revoke an unknown refresh token");
            return;
        }

        await sessions.EndAsync(token.UserId, token.FamilyId, "logout");
    }

    /// <summary>Password/external step done: hand out tokens, or an MFA challenge when enabled.</summary>
    public async Task<LoginResponse> IssueTokensOrChallengeAsync(ApplicationUser user)
    {
        if (user.TwoFactorEnabled)
        {
            return new LoginResponse(null, null, null, null, MfaRequired: true, MfaToken: jwtProvider.CreateMfaChallengeToken(user));
        }

        return await IssueTokensAsync(user);
    }

    public async Task<bool> VerifyAuthenticatorOrRecoveryCodeAsync(ApplicationUser user, string code)
    {
        var trimmed = code.Trim();
        if (await userManager.VerifyTwoFactorTokenAsync(user, userManager.Options.Tokens.AuthenticatorTokenProvider, trimmed.Replace(" ", string.Empty)))
            return true;

        // Recovery codes are stored exactly as issued (e.g. "53JHH-VWMJV"); only case is normalized.
        var redeemed = await userManager.RedeemTwoFactorRecoveryCodeAsync(user, trimmed.ToUpperInvariant());
        return redeemed.Succeeded;
    }

    /// <param name="familyId">The session to continue (a refresh); null starts a new one (a sign-in).</param>
    private async Task<LoginResponse> IssueTokensAsync(ApplicationUser user, Guid? familyId = null)
    {
        var sessionId = familyId ?? Guid.CreateVersion7();
        var accessToken = await jwtProvider.CreateToken(user, sessionId);
        var rawRefreshToken = JwtProvider.GenerateRefreshToken();
        var now = DateTimeOffset.UtcNow;

        var entity = new RefreshToken
        {
            TokenHash = JwtProvider.HashRefreshToken(rawRefreshToken),
            FamilyId = sessionId,
            CreatedAtUtc = now,
            ExpireAtUtc = now.AddDays(jwtParameter.RefreshTokenExpirationInDays),
            IsActive = true,
            UserId = user.Id,
            User = user,
        };

        dbContext.RefreshTokens.Add(entity);
        await dbContext.SaveChangesAsync();

        return new LoginResponse(
            AccessToken: accessToken,
            ExpiresAt: now.AddMinutes(jwtParameter.ExpirationInMinutes),
            RefreshToken: rawRefreshToken,
            RefreshTokenExpireAt: entity.ExpireAtUtc
        );
    }
}
