using System.Text;
using Kadans.Modules.Identity.Domain;
using Kadans.SharedKernel.Email;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.WebUtilities;
using Microsoft.Extensions.Options;

namespace Kadans.Modules.Identity.Features.Account;

/// <summary>Builds and sends the account emails. Tokens are Base64Url-encoded so they survive links.</summary>
internal sealed class IdentityEmails(
    IEmailSender sender,
    IOptions<EmailOptions> options,
    UserManager<ApplicationUser> userManager,
    ILogger<IdentityEmails> logger
)
{
    private string BaseUrl => options.Value.LinkBaseUrl.TrimEnd('/');

    public async Task SendConfirmationAsync(ApplicationUser user, CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(user.Email))
            return;

        var token = Encode(await userManager.GenerateEmailConfirmationTokenAsync(user));
        var link = $"{BaseUrl}/auth/confirm-email?userId={Uri.EscapeDataString(user.Id)}&token={token}";
        var texts = EmailTexts.For(user.PreferredLanguage);

        await SendAsync(
            user.Email,
            texts.ConfirmSubject,
            string.Format(texts.ConfirmBody, Greeting(user), link),
            cancellationToken
        );
    }

    public async Task SendPasswordResetAsync(ApplicationUser user, CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(user.Email))
            return;

        var token = Encode(await userManager.GeneratePasswordResetTokenAsync(user));
        var link = $"{BaseUrl}/auth/reset-password?email={Uri.EscapeDataString(user.Email)}&token={token}";
        var texts = EmailTexts.For(user.PreferredLanguage);

        await SendAsync(
            user.Email,
            texts.ResetSubject,
            string.Format(texts.ResetBody, Greeting(user), link),
            cancellationToken
        );
    }

    public async Task SendEmailChangeAsync(ApplicationUser user, string newEmail, CancellationToken cancellationToken = default)
    {
        var token = Encode(await userManager.GenerateChangeEmailTokenAsync(user, newEmail));
        var link = $"{BaseUrl}/users/me/email/confirm?newEmail={Uri.EscapeDataString(newEmail)}&token={token}";
        var texts = EmailTexts.For(user.PreferredLanguage);

        await SendAsync(
            newEmail,
            texts.ChangeSubject,
            string.Format(texts.ChangeBody, Greeting(user), link),
            cancellationToken
        );
    }

    public static string Encode(string token) => WebEncoders.Base64UrlEncode(Encoding.UTF8.GetBytes(token));

    public static string? Decode(string encoded)
    {
        try
        {
            return Encoding.UTF8.GetString(WebEncoders.Base64UrlDecode(encoded));
        }
        catch (FormatException)
        {
            return null;
        }
    }

    private static string Greeting(ApplicationUser user) => user.DisplayName ?? user.UserName ?? "there";

    private async Task SendAsync(string to, string subject, string text, CancellationToken cancellationToken)
    {
        var html = $"<p>{System.Net.WebUtility.HtmlEncode(text).Replace("\n", "<br>")}</p>";
        try
        {
            await sender.SendAsync(new OutgoingEmail(to, subject, html, text), cancellationToken);
        }
        catch (Exception ex)
        {
            // Never fail the calling flow because mail is down; the user can ask again.
            logger.LogError(ex, "Failed to send '{Subject}' to {To}", subject, to);
        }
    }
}
