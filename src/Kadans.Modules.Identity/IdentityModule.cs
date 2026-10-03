using System.Security.Claims;
using System.Text;
using Kadans.Modules.Identity.Domain;
using Kadans.Modules.Identity.Features.Account;
using Kadans.Modules.Identity.Features.Auth;
using Kadans.Modules.Identity.Features.Devices;
using Kadans.Modules.Identity.Features.Users;
using Kadans.Modules.Identity.Persistence;
using Kadans.Modules.Identity.Security;
using Kadans.SharedKernel.Modules;
using Kadans.SharedKernel.Persistence;
using Kadans.SharedKernel.Realtime;
using Kadans.SharedKernel.Security;
using Kadans.SharedKernel.Users;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.DataProtection.KeyManagement;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.IdentityModel.Tokens;
using Quartz;

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
                // Length is what makes a password hard to guess; the complexity rules stay Identity's defaults.
                options.Password.RequiredLength = 8;
                options.Lockout.MaxFailedAccessAttempts =
                    lockout.GetValue<int?>("MaxFailedAccessAttempts") ?? 5;
                options.Lockout.DefaultLockoutTimeSpan = TimeSpan.FromMinutes(
                    lockout.GetValue<int?>("DefaultLockoutMinutes") ?? 15
                );
                options.Lockout.AllowedForNewUsers =
                    lockout.GetValue<bool?>("AllowedForNewUsers") ?? true;
            })
            .AddEntityFrameworkStores<IdentityModuleDbContext>()
            // Password and username rules in the request's language (codes unchanged).
            .AddErrorDescriber<LocalizedIdentityErrorDescriber>()
            .AddDefaultTokenProviders();
        // The default token providers protect emailed-link tokens with Data Protection. Its default key store is
        // a folder inside the container, lost on every rebuild (and every link sent before it with it), so the
        // keys live in this module's schema instead – encrypted, since they end up in the backups too.
        services
            .AddDataProtection()
            .SetApplicationName("Kadans")
            .PersistKeysToDbContext<IdentityModuleDbContext>();
        services
            .AddOptions<KeyManagementOptions>()
            .Configure<IConfiguration>((options, config) =>
                options.XmlEncryptor = new KeyRingXmlEncryptor(KeyRingEncryption.DeriveKey(config))
            );

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
                // WebSockets cannot carry an Authorization header: the hub client sends ?access_token=.
                options.Events = new JwtBearerEvents
                {
                    OnMessageReceived = context =>
                    {
                        var accessToken = context.Request.Query["access_token"];
                        if (!string.IsNullOrEmpty(accessToken) && context.HttpContext.Request.Path.StartsWithSegments(RealtimeHub.Path))
                            context.Token = accessToken;
                        return Task.CompletedTask;
                    },
                    // A token is only as good as its session: signed out, signed out everywhere, password changed,
                    // deactivated – refused from the next request, not when the token expires. The client then
                    // refreshes, which fails the same way, and lands on sign-in. A token without a session (issued
                    // before sessions were stamped) is refused too; a refresh replaces it.
                    OnTokenValidated = async context =>
                    {
                        var registry = context.HttpContext.RequestServices.GetRequiredService<SessionRegistry>();
                        if (!Guid.TryParse(context.Principal?.FindFirstValue(SessionClaim.Type), out var sessionId)
                            || !await registry.IsOnAsync(sessionId, context.HttpContext.RequestAborted))
                            context.Fail("The session has ended.");
                    },
                };
            });

        services.AddSingleton<ExternalIdTokenValidator>();
        services.AddHttpClient<GoogleCodeExchange>(client => client.Timeout = TimeSpan.FromSeconds(15));
        services.AddScoped<JwtProvider>();
        services.AddSingleton<SessionRegistry>();
        services.AddScoped<Sessions>();
        services.AddScoped<Authentication>();
        services.AddScoped<ExternalAuthentication>();
        services.AddScoped<AccountSecurity>();
        services.AddScoped<IdentityEmails>();
        services.TryAddSingleton(TimeProvider.System);
        services.AddSingleton<EmailThrottle>();
        services.AddScoped<DeviceService>();
        services.AddScoped<IDevicePushTargets, DevicePushTargets>();
        services.AddScoped<IUserDirectory, UserDirectory>();
        services.AddScoped<UserManagement>();

        services.Configure<IdentityRetentionOptions>(configuration.GetSection(IdentityRetentionOptions.SectionName));
        services.Configure<AccountDeletionOptions>(configuration.GetSection(AccountDeletionOptions.SectionName));
        services.AddScoped<AccountDeletions>();
        services.AddQuartz(quartz =>
        {
            // Nightly, and once soon after a start so a server that restarts often still cleans up.
            quartz.AddJob<IdentityRetentionJob>(job => job.WithIdentity(IdentityRetentionJob.Key));
            quartz.AddTrigger(trigger =>
                trigger
                    .ForJob(IdentityRetentionJob.Key)
                    .WithIdentity("identity-retention-nightly", "identity")
                    .WithCronSchedule(Retention.NightlyCron, cron => cron.InTimeZone(TimeZoneInfo.Utc))
            );
            quartz.AddTrigger(trigger =>
                trigger
                    .ForJob(IdentityRetentionJob.Key)
                    .WithIdentity("identity-retention-startup", "identity")
                    .StartAt(DateBuilder.FutureDate(2, IntervalUnit.Minute))
            );

            // Accounts whose 7 days are over: erased within a quarter of an hour.
            quartz.AddJob<AccountErasureJob>(job => job.WithIdentity(AccountErasureJob.Key));
            quartz.AddTrigger(trigger =>
                trigger
                    .ForJob(AccountErasureJob.Key)
                    .WithIdentity("account-erasure-trigger", "identity")
                    .StartAt(DateBuilder.FutureDate(1, IntervalUnit.Minute))
                    .WithSimpleSchedule(s => s.WithIntervalInMinutes(15).RepeatForever())
            );
        });
    }

    public void MapEndpoints(IEndpointRouteBuilder endpoints)
    {
        endpoints.MapAuthRoutes();
        endpoints.MapUserRoutes();
        endpoints.MapAccountDeletionPages();
    }

    public async Task InitializeAsync(IServiceProvider services, CancellationToken cancellationToken)
    {
        await services.MigrateIfConfiguredAsync<IdentityModuleDbContext>(cancellationToken);
        await services.SeedInitialAdminAsync(); // needs the tables
    }
}
