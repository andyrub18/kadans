using Kadans.Modules.Identity.Domain;
using Kadans.SharedKernel.Users;
using Microsoft.AspNetCore.Identity;

namespace Kadans.Modules.Identity.Features.Users;

internal sealed class UserDirectory(UserManager<ApplicationUser> userManager) : IUserDirectory
{
    public async Task<UserSummary?> FindAsync(string userId, CancellationToken cancellationToken = default)
    {
        var user = await userManager.FindByIdAsync(userId);
        return user is null ? null : Summary(user);
    }

    public async Task<UserSummary?> FindByLoginAsync(string usernameOrEmail, CancellationToken cancellationToken = default)
    {
        var user = await userManager.FindByNameAsync(usernameOrEmail);
        if (user is null && await userManager.FindByEmailAsync(usernameOrEmail) is { EmailConfirmed: true } byEmail)
            user = byEmail;
        return user is null ? null : Summary(user);
    }

    private static UserSummary Summary(ApplicationUser user) =>
        new(user.Id, user.DisplayName, user.Email, user.TimeZoneId, user.PreferredLanguage, user.UserName, user.EmailConfirmed);
}
