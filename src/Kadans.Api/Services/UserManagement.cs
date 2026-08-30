using Kadans.SharedKernel.Security;
using Kadans.Api.Data;
using Kadans.Api.DTOs;
using Kadans.SharedKernel.Errors;
using Kadans.Api.Security;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using OneOf;

namespace Kadans.Api.Services;

public sealed class UserManagement(
    ILogger<UserManagement> logger,
    ApplicationDbContext dbContext,
    UserManager<IdentityUser> userManager,
    RoleManager<IdentityRole> roleManager,
    ICurrentUserService currentUserService,
    IHttpContextAccessor httpContextAccessor
)
{
    private const string AdminRoleName = "Admin";

    public Task<OneOf<ApplicationError, UserResponse>> RegisterUser(RegisterUserRequest request)
    {
        var createRequest = new CreateUserRequest(
            request.Username,
            request.Password,
            request.Email,
            null
        );

        return CreateUserInternal(createRequest, canManageRoles: false);
    }

    public async Task<OneOf<ApplicationError, UserResponse>> CreateUser(CreateUserRequest request)
    {
        if (!IsCurrentUserAdmin())
        {
            return new ApplicationError(
                ErrorTypes.Forbidden,
                "Only admins can create users via this endpoint."
            );
        }

        return await CreateUserInternal(request, canManageRoles: true);
    }

    private async Task<OneOf<ApplicationError, UserResponse>> CreateUserInternal(
        CreateUserRequest request,
        bool canManageRoles
    )
    {
        var requestedRoles = NormalizeRoles(request.Roles);
        if (!canManageRoles && requestedRoles.Length > 0)
        {
            logger.LogWarning(
                "User {UserId} attempted to assign roles while creating an account",
                currentUserService.UserId
            );
            return new ApplicationError(ErrorTypes.Forbidden, "Only admins can assign roles.");
        }

        var roleValidationError = await ValidateRolesAsync(request.Roles);
        if (roleValidationError is not null)
            return roleValidationError;

        var user = new IdentityUser
        {
            UserName = request.Username,
            Email = request.Email,
            LockoutEnabled = true,
        };

        await using var transaction = await dbContext.Database.BeginTransactionAsync();

        var createResult = await userManager.CreateAsync(user, request.Password);
        if (!createResult.Succeeded)
        {
            logger.LogWarning("Failed to create user {Username}", request.Username);
            return createResult.ToValidationError("Validation failed for creating user.");
        }

        if (requestedRoles.Length > 0)
        {
            var addRolesResult = await userManager.AddToRolesAsync(user, requestedRoles);
            if (!addRolesResult.Succeeded)
            {
                await transaction.RollbackAsync();
                logger.LogWarning("Failed to assign roles to user {Username}", request.Username);
                return addRolesResult.ToValidationError("Validation failed for creating user.");
            }
        }

        await transaction.CommitAsync();

        logger.LogInformation("User {UserId} created", user.Id);
        return await BuildUserResponseAsync(user);
    }

    public async Task<OneOf<ApplicationError, UserResponse>> UpdateUser(
        string userId,
        UpdateUserRequest request
    )
    {
        if (!IsCurrentUserAdmin())
        {
            logger.LogWarning(
                "Non-admin user {CurrentUserId} attempted to update user {TargetUserId}",
                currentUserService.UserId,
                userId
            );
            return new ApplicationError(
                ErrorTypes.Forbidden,
                "Only admins can update other users."
            );
        }

        return await UpdateUserInternal(userId, request, canManageRoles: true);
    }

    public async Task<OneOf<ApplicationError, UserResponse>> UpdateCurrentUser(
        UpdateSelfUserRequest request
    )
    {
        if (string.IsNullOrWhiteSpace(currentUserService.UserId))
        {
            return new ApplicationError(ErrorTypes.Unauthorized, "Unable to resolve current user.");
        }

        var updateRequest = new UpdateUserRequest(
            request.Username,
            request.Email,
            request.NewPassword,
            null
        );

        return await UpdateUserInternal(
            currentUserService.UserId,
            updateRequest,
            canManageRoles: false
        );
    }

    private async Task<OneOf<ApplicationError, UserResponse>> UpdateUserInternal(
        string userId,
        UpdateUserRequest request,
        bool canManageRoles
    )
    {
        var user = await userManager.FindByIdAsync(userId);
        if (user is null)
        {
            logger.LogWarning("User {UserId} not found for update", userId);
            return new ApplicationError(
                ErrorTypes.UserNotFound,
                $"User with id {userId} not found"
            );
        }

        if (!canManageRoles && request.Roles is not null)
        {
            logger.LogWarning(
                "User {UserId} attempted to update roles without admin privileges",
                currentUserService.UserId
            );
            return new ApplicationError(ErrorTypes.Forbidden, "Only admins can update roles.");
        }

        if (request.Roles is not null)
        {
            var roleValidationError = await ValidateRolesAsync(request.Roles);
            if (roleValidationError is not null)
                return roleValidationError;
        }

        await using var transaction = await dbContext.Database.BeginTransactionAsync();

        if (!string.IsNullOrWhiteSpace(request.Username) && request.Username != user.UserName)
        {
            var setUsernameResult = await userManager.SetUserNameAsync(user, request.Username);
            if (!setUsernameResult.Succeeded)
            {
                await transaction.RollbackAsync();
                logger.LogWarning("Failed to update username for user {UserId}", userId);
                return setUsernameResult.ToValidationError("Validation failed for updating user.");
            }
        }

        if (!string.IsNullOrWhiteSpace(request.Email) && request.Email != user.Email)
        {
            var setEmailResult = await userManager.SetEmailAsync(user, request.Email);
            if (!setEmailResult.Succeeded)
            {
                await transaction.RollbackAsync();
                logger.LogWarning("Failed to update email for user {UserId}", userId);
                return setEmailResult.ToValidationError("Validation failed for updating user.");
            }
        }

        if (!string.IsNullOrWhiteSpace(request.NewPassword))
        {
            var resetToken = await userManager.GeneratePasswordResetTokenAsync(user);
            var resetPasswordResult = await userManager.ResetPasswordAsync(
                user,
                resetToken,
                request.NewPassword
            );

            if (!resetPasswordResult.Succeeded)
            {
                await transaction.RollbackAsync();
                logger.LogWarning("Failed to reset password for user {UserId}", userId);
                return resetPasswordResult.ToValidationError(
                    "Validation failed for updating user."
                );
            }

            await RevokeActiveRefreshTokensAsync(user.Id);
        }

        if (request.Roles is not null)
        {
            var requestedRoles = NormalizeRoles(request.Roles);
            var currentRoles = await userManager.GetRolesAsync(user);

            var rolesToAdd = requestedRoles
                .Except(currentRoles, StringComparer.OrdinalIgnoreCase)
                .ToArray();
            var rolesToRemove = currentRoles
                .Except(requestedRoles, StringComparer.OrdinalIgnoreCase)
                .ToArray();

            if (rolesToRemove.Length > 0)
            {
                var removeRolesResult = await userManager.RemoveFromRolesAsync(user, rolesToRemove);
                if (!removeRolesResult.Succeeded)
                {
                    await transaction.RollbackAsync();
                    logger.LogWarning("Failed to remove roles for user {UserId}", userId);
                    return removeRolesResult.ToValidationError(
                        "Validation failed for updating user."
                    );
                }
            }

            if (rolesToAdd.Length > 0)
            {
                var addRolesResult = await userManager.AddToRolesAsync(user, rolesToAdd);
                if (!addRolesResult.Succeeded)
                {
                    await transaction.RollbackAsync();
                    logger.LogWarning("Failed to add roles for user {UserId}", userId);
                    return addRolesResult.ToValidationError("Validation failed for updating user.");
                }
            }
        }

        await transaction.CommitAsync();

        logger.LogInformation("User {UserId} updated", userId);
        return await BuildUserResponseAsync(user);
    }

    public async Task<OneOf<ApplicationError, UserResponse>> DeactivateUser(string userId)
    {
        if (!IsCurrentUserAdmin())
        {
            logger.LogWarning(
                "Non-admin user {CurrentUserId} attempted to deactivate user {TargetUserId}",
                currentUserService.UserId,
                userId
            );
            return new ApplicationError(
                ErrorTypes.Forbidden,
                "Only admins can deactivate other users."
            );
        }

        return await DeactivateUserInternal(userId);
    }

    public async Task<OneOf<ApplicationError, UserResponse>> DeactivateCurrentUser()
    {
        if (string.IsNullOrWhiteSpace(currentUserService.UserId))
        {
            return new ApplicationError(ErrorTypes.Unauthorized, "Unable to resolve current user.");
        }

        return await DeactivateUserInternal(currentUserService.UserId);
    }

    private async Task<OneOf<ApplicationError, UserResponse>> DeactivateUserInternal(string userId)
    {
        var user = await userManager.FindByIdAsync(userId);
        if (user is null)
        {
            logger.LogWarning("User {UserId} not found for deactivation", userId);
            return new ApplicationError(
                ErrorTypes.UserNotFound,
                $"User with id {userId} not found"
            );
        }

        await using var transaction = await dbContext.Database.BeginTransactionAsync();

        var lockoutEnabledResult = await userManager.SetLockoutEnabledAsync(user, true);
        if (!lockoutEnabledResult.Succeeded)
        {
            await transaction.RollbackAsync();
            logger.LogWarning("Failed to enable lockout for user {UserId}", userId);
            return lockoutEnabledResult.ToValidationError(
                "Validation failed for deactivating user."
            );
        }

        var lockoutResult = await userManager.SetLockoutEndDateAsync(user, DateTimeOffset.MaxValue);
        if (!lockoutResult.Succeeded)
        {
            await transaction.RollbackAsync();
            logger.LogWarning("Failed to deactivate user {UserId}", userId);
            return lockoutResult.ToValidationError("Validation failed for deactivating user.");
        }

        var securityStampResult = await userManager.UpdateSecurityStampAsync(user);
        if (!securityStampResult.Succeeded)
        {
            await transaction.RollbackAsync();
            logger.LogWarning("Failed to update security stamp for user {UserId}", userId);
            return securityStampResult.ToValidationError(
                "Validation failed for deactivating user."
            );
        }

        await RevokeActiveRefreshTokensAsync(user.Id);
        await transaction.CommitAsync();

        logger.LogInformation("User {UserId} deactivated", userId);
        return await BuildUserResponseAsync(user);
    }

    private async Task<UserResponse> BuildUserResponseAsync(IdentityUser user)
    {
        var roles = await userManager.GetRolesAsync(user);
        var lockoutEndDate = await userManager.GetLockoutEndDateAsync(user);
        var isActive = !lockoutEndDate.HasValue || lockoutEndDate.Value <= DateTimeOffset.UtcNow;

        return new UserResponse(
            user.Id,
            user.UserName ?? string.Empty,
            user.Email,
            isActive,
            [.. roles]
        );
    }

    private async Task<ApplicationError?> ValidateRolesAsync(IReadOnlyCollection<string>? roles)
    {
        var requestedRoles = NormalizeRoles(roles);
        if (requestedRoles.Length == 0)
            return null;

        var errors = new List<(string, string)>();
        foreach (var role in requestedRoles)
        {
            if (!await roleManager.RoleExistsAsync(role))
            {
                errors.Add(("InvalidRole", $"Role '{role}' does not exist."));
            }
        }

        return errors.Count == 0
            ? null
            : new ValidationError(
                ErrorTypes.ValidationError,
                "One or more roles are invalid.",
                errors
            );
    }

    private static string[] NormalizeRoles(IReadOnlyCollection<string>? roles) =>
        roles
            ?.Where(role => !string.IsNullOrWhiteSpace(role))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToArray()
        ?? [];

    private Task<int> RevokeActiveRefreshTokensAsync(string userId) =>
        dbContext
            .RefreshTokens.Where(refreshToken =>
                refreshToken.UserId == userId && refreshToken.IsActive
            )
            .ExecuteUpdateAsync(setters =>
                setters.SetProperty(refreshToken => refreshToken.IsActive, false)
            );

    private bool IsCurrentUserAdmin() =>
        httpContextAccessor.HttpContext?.User.IsInRole(AdminRoleName) is true;
}

public static class IdentityResultExtensions
{
    extension(IdentityResult result)
    {
        public ValidationError ToValidationError(string message) =>
            new(
                ErrorTypes.ValidationError,
                message,
                [.. result.Errors.Select(error => (error.Code, error.Description))]
            );
    }
}
