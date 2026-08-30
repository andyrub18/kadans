namespace Kadans.SharedKernel.Email;

public sealed record OutgoingEmail(string To, string Subject, string HtmlBody, string TextBody);

public interface IEmailSender
{
    Task SendAsync(OutgoingEmail email, CancellationToken cancellationToken = default);
}
