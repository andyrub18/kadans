using System.Text.Encodings.Web;
using System.Text.Json;
using Kadans.Modules.Identity.Features.Account;

namespace Kadans.Modules.Identity.Features.Auth;

/// <summary>
/// The two pages the account emails land on, self-contained HTML in the user's language.
/// Query values are attacker-controlled: HTML goes through <see cref="HtmlEncoder"/> and the
/// script payload through <see cref="JsonSerializer"/> (whose default encoder escapes HTML).
/// </summary>
internal static class AuthPages
{
    /// <summary>A one-sentence page: the outcome of a link, good or bad, in the account's language.</summary>
    public static string Message(string text) => Page($"<p>{HtmlEncoder.Default.Encode(text)}</p>");

    public static string Confirmed(EmailTexts texts) =>
        Page($"<p>{HtmlEncoder.Default.Encode(texts.ConfirmedPage)}</p>");

    /// <summary>A minimal form so the emailed link works from any browser, plus the app link.</summary>
    public static string ResetPassword(EmailTexts texts, string email, string token)
    {
        var appLink = $"kadans://auth/reset-password?email={Uri.EscapeDataString(email)}&token={Uri.EscapeDataString(token)}";
        var payload = JsonSerializer.Serialize(new { email, token });
        var done = JsonSerializer.Serialize(texts.ResetPageDone);

        return Page($$"""
            <h3>{{HtmlEncoder.Default.Encode(texts.ResetPageTitle)}}</h3>
            <form id="f">
              <input id="p" type="password" minlength="8" required autocomplete="new-password"
                     placeholder="{{HtmlEncoder.Default.Encode(texts.ResetPagePassword)}}">
              <button>{{HtmlEncoder.Default.Encode(texts.ResetPageButton)}}</button>
            </form>
            <p id="m"></p>
            <p><a href="{{HtmlEncoder.Default.Encode(appLink)}}">{{HtmlEncoder.Default.Encode(texts.OpenInApp)}}</a></p>
            <script>
            const c = {{payload}};
            document.getElementById('f').addEventListener('submit', async (e) => {
              e.preventDefault();
              const r = await fetch('/auth/reset-password', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ email: c.email, token: c.token, newPassword: document.getElementById('p').value }),
              });
              const m = document.getElementById('m');
              if (r.ok) {
                document.getElementById('f').hidden = true;
                m.textContent = {{done}};
              } else {
                const problem = await r.json().catch(() => null);
                m.textContent = (problem && (problem.detail || problem.title)) || r.status;
              }
            });
            </script>
            """);
    }

    /// <summary>Google Play's web route to deletion, for people without the app: an address, then a link to it.</summary>
    public static string DeleteRequest(DeletionTexts texts) =>
        Page($$"""
            <h3>{{HtmlEncoder.Default.Encode(texts.PageTitle)}}</h3>
            <p>{{HtmlEncoder.Default.Encode(texts.PageIntro)}}</p>
            <form method="post" action="/account/delete">
              <input name="email" type="email" required autocomplete="email" placeholder="{{HtmlEncoder.Default.Encode(texts.PageEmail)}}">
              <button>{{HtmlEncoder.Default.Encode(texts.PageSend)}}</button>
            </form>
            """);

    /// <summary>The emailed link's page: what will happen, and a button. Opening it deletes nothing (mail scanners open links).</summary>
    public static string DeleteConfirm(DeletionTexts texts, string accountName, string eraseOn, string userId, string token) =>
        Page($$"""
            <h3>{{HtmlEncoder.Default.Encode(texts.PageTitle)}}</h3>
            <p>{{HtmlEncoder.Default.Encode(string.Format(texts.ConfirmQuestion, accountName, eraseOn))}}</p>
            <form method="post" action="/account/delete/confirm">
              <input type="hidden" name="userId" value="{{HtmlEncoder.Default.Encode(userId)}}">
              <input type="hidden" name="token" value="{{HtmlEncoder.Default.Encode(token)}}">
              <button style="background:#b3261e">{{HtmlEncoder.Default.Encode(texts.ConfirmButton)}}</button>
            </form>
            """);

    private static string Page(string body) => $$"""
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1"><title>Kadans</title>
        <style>
          body{font-family:system-ui,sans-serif;max-width:26rem;margin:3rem auto;padding:0 1rem;color:#1c1b1f}
          input,button{font-size:1rem;padding:.6rem;width:100%;box-sizing:border-box;margin-top:.5rem}
          button{background:#6750a4;color:#fff;border:0;border-radius:.5rem;cursor:pointer}
          a{color:#6750a4}
        </style></head><body><h2>Kadans</h2>{{body}}</body></html>
        """;
}
