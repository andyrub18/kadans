using Microsoft.AspNetCore.Identity;

namespace Kadans.Api.Security;

public sealed class InitialAdminOptions
{
    public bool Enabled { get; init; }
    public string Username { get; init; } = string.Empty;
    public string? Email { get; init; }
    public string Password { get; init; } = string.Empty;
}

public static class IdentityBootstrapper
{
    private const string InitialAdminSectionName = "InitialAdmin";
    private const string AdminRoleName = "Admin";

    public static async Task SeedInitialAdminAsync(this WebApplication app)
    {
        using var scope = app.Services.CreateScope();
        var services = scope.ServiceProvider;

        var logger = services
            .GetRequiredService<ILoggerFactory>()
            .CreateLogger("IdentityBootstrapper");

        var configuration = services.GetRequiredService<IConfiguration>();
        var options = configuration.GetSection(InitialAdminSectionName).Get<InitialAdminOptions>();

        if (options?.Enabled is not true)
        {
            logger.LogInformation(
                "Initial admin seeding is disabled. Set {Section}:Enabled=true to enable it.",
                InitialAdminSectionName
            );
            return;
        }

        if (
            string.IsNullOrWhiteSpace(options.Username)
            || string.IsNullOrWhiteSpace(options.Password)
        )
        {
            logger.LogWarning(
                "Initial admin seeding is enabled but Username/Password are missing in {Section}.",
                InitialAdminSectionName
            );
            return;
        }

        var roleManager = services.GetRequiredService<RoleManager<IdentityRole>>();
        var userManager = services.GetRequiredService<UserManager<IdentityUser>>();

        if (!await roleManager.RoleExistsAsync(AdminRoleName))
        {
            var createRoleResult = await roleManager.CreateAsync(new IdentityRole(AdminRoleName));
            if (!createRoleResult.Succeeded)
            {
                throw new InvalidOperationException(
                    $"Failed to create '{AdminRoleName}' role: {FormatErrors(createRoleResult.Errors)}"
                );
            }

            logger.LogInformation("Created role {RoleName}", AdminRoleName);
        }

        var existingAdmins = await userManager.GetUsersInRoleAsync(AdminRoleName);
        if (existingAdmins.Count > 0)
        {
            logger.LogInformation(
                "At least one admin user already exists. Initial admin seeding skipped."
            );
            return;
        }

        var user = await userManager.FindByNameAsync(options.Username);
        if (user is null)
        {
            user = new IdentityUser
            {
                UserName = options.Username,
                Email = options.Email,
                LockoutEnabled = true,
            };

            var createUserResult = await userManager.CreateAsync(user, options.Password);
            if (!createUserResult.Succeeded)
            {
                throw new InvalidOperationException(
                    $"Failed to create initial admin user: {FormatErrors(createUserResult.Errors)}"
                );
            }

            logger.LogInformation("Created initial admin user {Username}", options.Username);
        }

        var addRoleResult = await userManager.AddToRoleAsync(user, AdminRoleName);
        if (!addRoleResult.Succeeded)
        {
            throw new InvalidOperationException(
                $"Failed to assign '{AdminRoleName}' role to initial user: {FormatErrors(addRoleResult.Errors)}"
            );
        }

        // Ensure seeded admin is active for immediate testing.
        await userManager.SetLockoutEndDateAsync(user, null);

        logger.LogInformation("Initial admin user {Username} is ready", options.Username);
    }

    private static string FormatErrors(IEnumerable<IdentityError> errors) =>
        string.Join("; ", errors.Select(error => $"{error.Code}: {error.Description}"));
}
