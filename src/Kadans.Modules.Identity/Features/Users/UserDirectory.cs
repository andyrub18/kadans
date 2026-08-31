using Kadans.Modules.Identity.Domain;
using Kadans.SharedKernel.Users;
using Microsoft.AspNetCore.Identity;

namespace Kadans.Modules.Identity.Features.Users;

internal sealed class UserDirectory(UserManager<ApplicationUser> userManager) : IUserDirectory
{
    public async Task<UserSummary?> FindAsync(string userId, CancellationToken cancellationToken = default)
    {
        var user = await userManager.FindByIdAsync(userId);
        return user is null ? null : new UserSummary(user.Id, user.DisplayName, user.Email, user.TimeZoneId);
    }
}
