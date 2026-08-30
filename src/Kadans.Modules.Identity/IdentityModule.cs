using System.Text;
using Kadans.Modules.Identity.Domain;
using Kadans.Modules.Identity.Features.Account;
using Kadans.Modules.Identity.Features.Auth;
using Kadans.Modules.Identity.Features.Devices;
using Kadans.Modules.Identity.Features.Users;
using Kadans.Modules.Identity.Persistence;
using Kadans.Modules.Identity.Security;
using Kadans.SharedKernel.Modules;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.IdentityModel.Tokens;

namespace Kadans.Modules.Identity;

/// <summary>Users, credentials, tokens and profile. Owns the <c>identity</c> schema.</summary>
public sealed class IdentityModule : IModule
{
    public void AddServices(IServiceCollection services, IConfiguration configuration)
    {
        services.AddDbContext<IdentityModuleDbContext>(options =>
            options.UseNpgsql(
                configuration.GetConnectionString("kadans"),
                npgsql =>
                    npgsql.MigrationsHistoryTable(
                        "__ef_migrations_history",
                        IdentityModuleDbContext.Schema
                    )
            )
        );

        var lockout = configuration.GetSection("Identity:Lockout");
        services
            .AddIdentity<ApplicationUser, IdentityRole>(options =>
            {
                options.User.RequireUniqueEmail = true;
                options.Lockout.MaxFailedAccessAttempts =
                    lockout.GetValue<int?>("MaxFailedAccessAttempts") ?? 5;
                options.Lockout.DefaultLockoutTimeSpan = TimeSpan.FromMinutes(
                    lockout.GetValue<int?>("DefaultLockoutMinutes") ?? 15
                );
                options.Lockout.AllowedForNewUsers =
                    lockout.GetValue<bool?>("AllowedForNewUsers") ?? true;
            })
            .AddEntityFrameworkStores<IdentityModuleDbContext>()
            .AddDefaultTokenProviders();

        services.ConfigureOptions<JwtParameterOptionsSetup>();
        services.Configure<ExternalAuthOptions>(configuration.GetSection(ExternalAuthOptions.SectionName));
        services
            .AddAuthentication(options =>
            {
                options.DefaultAuthenticateScheme = JwtBearerDefaults.AuthenticationScheme;
                options.DefaultChallengeScheme = JwtBearerDefaults.AuthenticationScheme;
            })
            .AddJwtBearer(options =>
            {
                options.TokenValidationParameters = new()
                {
                    ValidateIssuer = true,
                    ValidateAudience = true,
                    ValidateLifetime = true,
                    ValidateIssuerSigningKey = true,
                    ValidIssuer = configuration["Jwt:Issuer"],
                    ValidAudience = configuration["Jwt:Audience"],
                    IssuerSigningKey = new SymmetricSecurityKey(
                        Encoding.UTF8.GetBytes(configuration["Jwt:Key"]!)
                    ),
                };
            });

        services.AddSingleton<ExternalIdTokenValidator>();
        services.AddScoped<JwtProvider>();
        services.AddScoped<Authentication>();
        services.AddScoped<ExternalAuthentication>();
        services.AddScoped<AccountSecurity>();
        services.AddScoped<IdentityEmails>();
        services.AddScoped<DeviceService>();
        services.AddScoped<UserManagement>();
    }

    public void MapEndpoints(IEndpointRouteBuilder endpoints)
    {
        endpoints.MapAuthRoutes();
        endpoints.MapUserRoutes();
    }

    public Task InitializeAsync(IServiceProvider services, CancellationToken cancellationToken) =>
        services.SeedInitialAdminAsync();
}
