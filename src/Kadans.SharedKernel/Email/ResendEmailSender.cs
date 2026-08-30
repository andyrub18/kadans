using Microsoft.Extensions.Options;
using Resend;

namespace Kadans.SharedKernel.Email;

public sealed class ResendEmailSender(IResend resend, IOptions<EmailOptions> options) : IEmailSender
{
    public async Task SendAsync(OutgoingEmail email, CancellationToken cancellationToken = default)
    {
        var message = new EmailMessage
        {
            From = options.Value.From,
            Subject = email.Subject,
            HtmlBody = email.HtmlBody,
            TextBody = email.TextBody,
        };
        message.To.Add(email.To);

        var response = await resend.EmailSendAsync(message, cancellationToken);
        if (!response.Success)
        {
            throw new InvalidOperationException(
                $"Resend refused the message to {email.To}: {response.Exception?.Message}",
                response.Exception
            );
        }
    }
}
