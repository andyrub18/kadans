using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Resend;

namespace Kadans.SharedKernel.Email;

public static class EmailServiceCollectionExtensions
{
    public static IServiceCollection AddKadansEmail(
        this IServiceCollection services,
        IConfiguration configuration
    )
    {
        var section = configuration.GetSection(EmailOptions.SectionName);
        services.Configure<EmailOptions>(section);

        var options = section.Get<EmailOptions>() ?? new EmailOptions();
        if (string.Equals(options.Provider, "Resend", StringComparison.OrdinalIgnoreCase))
        {
            if (string.IsNullOrWhiteSpace(options.Resend.ApiKey))
                throw new InvalidOperationException("Email:Provider is Resend but Email:Resend:ApiKey is not set.");

            services.AddResend(o => o.ApiToken = options.Resend.ApiKey);
            services.AddScoped<IEmailSender, ResendEmailSender>();
        }
        else
        {
            services.AddSingleton<IEmailSender, LoggingEmailSender>();
        }

        return services;
    }
}
