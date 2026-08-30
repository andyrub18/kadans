using Kadans.Api.BackgroundTasks;
using Kadans.Api.Data;
using Kadans.Api.DTOs;
using Kadans.SharedKernel.Errors;
using Kadans.Api.Security;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using OneOf;

namespace Kadans.Api.Services;

public sealed class Authentication(
    ILogger<Authentication> logger,
    JwtProvider jwtProvider,
    UserManager<IdentityUser> userManager,
    SignInManager<IdentityUser> signInManager,
    ApplicationDbContext dbContext,
    IOptions<JwtParameter> jwtParameterOptions,
    IBackgroundTaskQueue taskQueue
)
{
    private readonly JwtParameter jwtParameter = jwtParameterOptions.Value;

    public async Task<OneOf<ApplicationError, LoginResponse>> Login(LoginRequest request)
    {
        var user = await userManager.FindByNameAsync(request.Username);

        if (user is null)
        {
            logger.LogWarning("User with username {Username} not found", request.Username);
            return new ApplicationError(
                ErrorTypes.InvalidCredentials,
                "Invalid username or password"
            );
        }

        if (await userManager.IsLockedOutAsync(user))
        {
            logger.LogWarning("Inactive user {Username} attempted to sign in", request.Username);
            return new ApplicationError(ErrorTypes.UserInactive, "User is deactivated");
        }

        var signInResult = await signInManager.CheckPasswordSignInAsync(
            user,
            request.Password,
            lockoutOnFailure: true
        );

        if (signInResult.IsLockedOut)
        {
            logger.LogWarning("User {Username} is locked out", request.Username);
            return new ApplicationError(ErrorTypes.UserInactive, "User is deactivated");
        }

        if (!signInResult.Succeeded)
        {
            logger.LogWarning("Invalid password for user {Username}", request.Username);
            return new ApplicationError(
                ErrorTypes.InvalidCredentials,
                "Invalid username or password"
            );
        }

        return await IssueTokensAsync(user);
    }

    public async Task<OneOf<ApplicationError, LoginResponse>> RefreshToken(
        RefreshTokenRequest request
    )
    {
        var refreshTokenEntity = await dbContext
            .RefreshTokens.Include(rt => rt.User)
            .FirstOrDefaultAsync(rt => rt.Token == request.RefreshToken);

        if (refreshTokenEntity?.IsActive is not true)
        {
            logger.LogWarning("Invalid refresh token: {RefreshToken}", request.RefreshToken);
            return new ApplicationError(ErrorTypes.InvalidCredentials, "Invalid refresh token");
        }

        if (refreshTokenEntity.ExpireAtUtc <= DateTimeOffset.UtcNow)
        {
            refreshTokenEntity.IsActive = false;
            await dbContext.SaveChangesAsync();

            logger.LogWarning("Expired refresh token: {RefreshToken}", request.RefreshToken);
            return new ApplicationError(ErrorTypes.InvalidCredentials, "Invalid refresh token");
        }

        var user = refreshTokenEntity.User;

        if (await userManager.IsLockedOutAsync(user))
        {
            refreshTokenEntity.IsActive = false;
            await dbContext.SaveChangesAsync();

            logger.LogWarning("Inactive user {UserId} attempted to refresh a token", user.Id);
            return new ApplicationError(ErrorTypes.UserInactive, "User is deactivated");
        }

        refreshTokenEntity.IsActive = false;

        return await IssueTokensAsync(user);
    }

    public async Task RevokeRefreshToken(string refreshToken)
    {
        var refreshTokenEntity = await dbContext.RefreshTokens.FirstOrDefaultAsync(rt =>
            rt.Token == refreshToken
        );

        if (refreshTokenEntity is not null)
        {
            refreshTokenEntity.IsActive = false;
            await dbContext.SaveChangesAsync();
            logger.LogInformation("Refresh token revoked: {RefreshToken}", refreshToken);
        }
        else
        {
            logger.LogWarning(
                "Attempted to revoke non-existent refresh token: {RefreshToken}",
                refreshToken
            );
        }
    }

    private async Task<LoginResponse> IssueTokensAsync(IdentityUser user)
    {
        var token = await jwtProvider.CreateToken(user);

        var refreshToken = JwtProvider.GenerateRefreshToken();

        var refreshTokenEntity = new RefreshToken
        {
            Token = refreshToken,
            ExpireAtUtc = DateTimeOffset.UtcNow.AddDays(7),
            CreatedAtUtc = DateTimeOffset.UtcNow,
            IsActive = true,
            UserId = user.Id,
            User = user,
        };

        dbContext.RefreshTokens.Add(refreshTokenEntity);
        await dbContext.SaveChangesAsync();

        EnqueueDeactivateOlderRefreshTokens(user.Id, refreshTokenEntity.Id);

        return new LoginResponse(
            AccessToken: token,
            ExpiresAt: DateTimeOffset.UtcNow.AddMinutes(jwtParameter.ExpirationInMinutes),
            RefreshToken: refreshToken,
            RefreshTokenExpireAt: refreshTokenEntity.ExpireAtUtc
        );
    }

    private void EnqueueDeactivateOlderRefreshTokens(string userId, int currentRefreshTokenId) =>
        taskQueue.EnqueueBackgroundWorkItem(
            async (sp, ct) =>
            {
                var dbContext = sp.GetRequiredService<ApplicationDbContext>();
                await dbContext
                    .RefreshTokens.Where(rt =>
                        rt.UserId == userId && rt.IsActive && rt.Id != currentRefreshTokenId
                    )
                    .ExecuteUpdateAsync(
                        setters => setters.SetProperty(rt => rt.IsActive, false),
                        ct
                    );
            }
        );
}
