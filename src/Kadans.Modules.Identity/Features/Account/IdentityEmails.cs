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

        await SendAsync(
            user.Email,
            "Confirm your Kadans email",
            $"Welcome to Kadans, {Greeting(user)}. Confirm your email address by opening this link:\n{link}\n\nIf you did not create this account, ignore this message.",
            cancellationToken
        );
    }

    public async Task SendPasswordResetAsync(ApplicationUser user, CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(user.Email))
            return;

        var token = Encode(await userManager.GeneratePasswordResetTokenAsync(user));
        var link = $"{BaseUrl}/auth/reset-password?email={Uri.EscapeDataString(user.Email)}&token={token}";

        await SendAsync(
            user.Email,
            "Reset your Kadans password",
            $"Hi {Greeting(user)}, someone asked to reset the password of this account. Open this link to choose a new one:\n{link}\n\nThe link expires soon. If it wasn't you, you can ignore this message; your password stays unchanged.",
            cancellationToken
        );
    }

    public async Task SendEmailChangeAsync(ApplicationUser user, string newEmail, CancellationToken cancellationToken = default)
    {
        var token = Encode(await userManager.GenerateChangeEmailTokenAsync(user, newEmail));
        var link = $"{BaseUrl}/users/me/email/confirm?newEmail={Uri.EscapeDataString(newEmail)}&token={token}";

        await SendAsync(
            newEmail,
            "Confirm your new Kadans email",
            $"Hi {Greeting(user)}, confirm that this is your new email address by opening this link:\n{link}\n\nIf you did not request this change, ignore this message.",
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
