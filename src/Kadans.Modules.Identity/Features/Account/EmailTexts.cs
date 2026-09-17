namespace Kadans.Modules.Identity.Features.Account;

/// <summary>
/// Account email wording per language ({0} = greeting name, {1} = link; in the changed-notice, {1} = the new address).
/// Languages: en (default), fr, ht (Kreyòl Ayisyen).
/// </summary>
internal sealed record EmailTexts(
    string ConfirmSubject,
    string ConfirmBody,
    string ResetSubject,
    string ResetBody,
    string ChangeSubject,
    string ChangeBody,
    string ConfirmedPage,
    string ResetPageTitle,
    string ResetPagePassword,
    string ResetPageButton,
    string ResetPageDone,
    string OpenInApp,
    string EmailChangedPage,
    string ChangedNoticeSubject,
    string ChangedNoticeBody
)
{
    public static EmailTexts For(string? language) =>
        language switch
        {
            "fr" => French,
            "ht" => Creole,
            _ => English,
        };

    private static readonly EmailTexts English = new(
        "Confirm your Kadans email",
        "Welcome to Kadans, {0}. Confirm your email address by opening this link:\n{1}\n\nIf you did not create this account, ignore this message.",
        "Reset your Kadans password",
        "Hi {0}, someone asked to reset the password of this account. Open this link to choose a new one:\n{1}\n\nThe link expires soon. If it wasn't you, you can ignore this message; your password stays unchanged.",
        "Confirm your new Kadans email",
        "Hi {0}, confirm that this is your new email address by opening this link:\n{1}\n\nIf you did not request this change, ignore this message.",
        "Your email is confirmed. You can go back to the app.",
        "Choose a new Kadans password",
        "New password",
        "Reset password",
        "Password changed. You can sign in to Kadans now.",
        "Open in the Kadans app",
        "Your email address has been changed. You can go back to Kadans.",
        "Your Kadans email address was changed",
        "Hi {0}, the email address of your Kadans account was just changed to {1}.\n\nIf that was you, there is nothing to do. If it was not, sign in and change your password right away."
    );

    private static readonly EmailTexts French = new(
        "Confirmez votre adresse e-mail Kadans",
        "Bienvenue sur Kadans, {0}. Confirmez votre adresse e-mail en ouvrant ce lien :\n{1}\n\nSi vous n'avez pas créé ce compte, ignorez ce message.",
        "Réinitialisez votre mot de passe Kadans",
        "Bonjour {0}, quelqu'un a demandé à réinitialiser le mot de passe de ce compte. Ouvrez ce lien pour en choisir un nouveau :\n{1}\n\nLe lien expire bientôt. Si ce n'était pas vous, ignorez ce message ; votre mot de passe reste inchangé.",
        "Confirmez votre nouvelle adresse e-mail Kadans",
        "Bonjour {0}, confirmez que ceci est votre nouvelle adresse e-mail en ouvrant ce lien :\n{1}\n\nSi vous n'avez pas demandé ce changement, ignorez ce message.",
        "Votre adresse e-mail est confirmée. Vous pouvez retourner dans l'application.",
        "Choisissez un nouveau mot de passe Kadans",
        "Nouveau mot de passe",
        "Réinitialiser le mot de passe",
        "Mot de passe changé. Vous pouvez maintenant vous connecter à Kadans.",
        "Ouvrir dans l'application Kadans",
        "Votre adresse e-mail a été modifiée. Vous pouvez retourner dans Kadans.",
        "L'adresse e-mail de votre compte Kadans a changé",
        "Bonjour {0}, l'adresse e-mail de votre compte Kadans vient d'être remplacée par {1}.\n\nSi c'était vous, il n'y a rien à faire. Sinon, connectez-vous et changez votre mot de passe sans attendre."
    );

    private static readonly EmailTexts Creole = new(
        "Konfime imèl Kadans ou",
        "Byenveni nan Kadans, {0}. Konfime adrès imèl ou lè ou ouvri lyen sa a:\n{1}\n\nSi se pa ou menm ki te kreye kont sa a, inyore mesaj sa a.",
        "Reyinisyalize modpas Kadans ou",
        "Bonjou {0}, yon moun mande pou reyinisyalize modpas kont sa a. Ouvri lyen sa a pou chwazi yon nouvo modpas:\n{1}\n\nLyen an ap ekspire talè. Si se pa ou menm, ou ka inyore mesaj sa a; modpas ou rete menm jan.",
        "Konfime nouvo imèl Kadans ou",
        "Bonjou {0}, konfime ke sa a se nouvo adrès imèl ou lè ou ouvri lyen sa a:\n{1}\n\nSi ou pa t mande chanjman sa a, inyore mesaj sa a.",
        "Imèl ou konfime. Ou ka retounen nan aplikasyon an.",
        "Chwazi yon nouvo modpas Kadans",
        "Nouvo modpas",
        "Reyinisyalize modpas la",
        "Modpas la chanje. Ou ka konekte nan Kadans kounye a.",
        "Ouvri nan aplikasyon Kadans lan",
        "Adrès imèl ou chanje. Ou ka retounen nan Kadans.",
        "Adrès imèl kont Kadans ou chanje",
        "Bonjou {0}, adrès imèl kont Kadans ou fèk chanje pou {1}.\n\nSi se ou menm ki fè sa, pa gen anyen pou fè. Si se pa ou menm, konekte epi chanje modpas ou touswit."
    );
}
