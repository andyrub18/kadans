using Microsoft.Extensions.Logging;

namespace Kadans.SharedKernel.Email;

/// <summary>Development sender: writes the message to the log instead of sending it.</summary>
public sealed class LoggingEmailSender(ILogger<LoggingEmailSender> logger) : IEmailSender
{
    public Task SendAsync(OutgoingEmail email, CancellationToken cancellationToken = default)
    {
        logger.LogInformation(
            "EMAIL (not sent) to {To} | {Subject}\n{Body}",
            email.To,
            email.Subject,
            email.TextBody
        );
        return Task.CompletedTask;
    }
}
