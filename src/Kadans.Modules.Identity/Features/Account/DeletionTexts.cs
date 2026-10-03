namespace Kadans.Modules.Identity.Features.Account;

/// <summary>
/// Account deletion wording per language: the emails and the web pages (Google Play wants deletion reachable without
/// the app). {0} = greeting name; {1} = the erasure date, or the link in <see cref="LinkBody"/>.
/// </summary>
internal sealed record DeletionTexts(
    string LinkSubject,
    string LinkBody,
    string ScheduledSubject,
    string ScheduledBody,
    string ErasedSubject,
    string ErasedBody,
    string PageTitle,
    string PageIntro,
    string PageEmail,
    string PageSend,
    string PageSent,
    string ConfirmQuestion,
    string ConfirmButton,
    string ScheduledPage,
    string InvalidLink,
    string[] Months,
    string DatePattern
)
{
    /// <summary>"October 10, 2026", "10 octobre 2026", "10 oktòb 2026", in the account's time zone. No culture data
    /// needed: the server may have none for Kreyòl.</summary>
    public string Date(DateTimeOffset at, TimeZoneInfo zone)
    {
        var local = TimeZoneInfo.ConvertTime(at, zone);
        return string.Format(DatePattern, local.Day, Months[local.Month - 1], local.Year);
    }

    public static DeletionTexts For(string? language) =>
        language switch
        {
            "fr" => French,
            "ht" => Creole,
            _ => English,
        };

    private static readonly DeletionTexts English = new(
        "Confirm the deletion of your Kadans account",
        "Hi {0}, someone asked to delete this Kadans account. If it was you, open this link to confirm:\n{1}\n\nIf it wasn't you, ignore this message: nothing changes.",
        "Your Kadans account will be erased",
        "Hi {0}, your Kadans account is closed, and it will be erased with everything in it (todos, focus history, budget) on {1}.\n\nChanged your mind? Sign in to Kadans before then and choose to keep it.\n\nDeleting the account does not cancel a subscription bought in Google Play or the App Store: cancel it there.",
        "Your Kadans account has been erased",
        "Hi {0}, your Kadans account and everything in it have been erased, as you asked. Our backups forget it within 14 days.\n\nThank you for using Kadans.",
        "Delete your Kadans account",
        "Enter the email address of your account and we will send you a link to confirm. Your account is closed at once and erased with everything in it (todos, focus history, budget) 7 days later; sign in before then to keep it.",
        "Email address",
        "Send the link",
        "If an account uses this address, we have sent it a link to confirm.",
        "Delete the Kadans account {0}? It is closed at once and erased with everything in it on {1}, unless you sign in before then to keep it.",
        "Delete my account",
        "Your account is closed and will be erased on {1}. To keep it, sign in to Kadans before then.",
        "This link is invalid or expired.",
        ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"],
        "{1} {0}, {2}"
    );

    private static readonly DeletionTexts French = new(
        "Confirmez la suppression de votre compte Kadans",
        "Bonjour {0}, quelqu'un a demandé la suppression de ce compte Kadans. Si c'était vous, ouvrez ce lien pour confirmer :\n{1}\n\nSi ce n'était pas vous, ignorez ce message : rien ne change.",
        "Votre compte Kadans va être effacé",
        "Bonjour {0}, votre compte Kadans est fermé, et il sera effacé avec tout son contenu (tâches, historique de concentration, budget) le {1}.\n\nVous avez changé d'avis ? Connectez-vous à Kadans avant cette date et choisissez de le garder.\n\nSupprimer le compte n'annule pas un abonnement acheté sur Google Play ou l'App Store : annulez-le là-bas.",
        "Votre compte Kadans a été effacé",
        "Bonjour {0}, votre compte Kadans et tout son contenu ont été effacés, comme vous l'avez demandé. Nos sauvegardes l'oublient d'ici 14 jours.\n\nMerci d'avoir utilisé Kadans.",
        "Supprimer votre compte Kadans",
        "Saisissez l'adresse e-mail de votre compte : nous vous enverrons un lien de confirmation. Votre compte est fermé aussitôt et effacé avec tout son contenu (tâches, historique de concentration, budget) 7 jours plus tard ; connectez-vous avant pour le garder.",
        "Adresse e-mail",
        "Envoyer le lien",
        "Si un compte utilise cette adresse, nous lui avons envoyé un lien de confirmation.",
        "Supprimer le compte Kadans {0} ? Il est fermé aussitôt et effacé avec tout son contenu le {1}, sauf si vous vous connectez avant pour le garder.",
        "Supprimer mon compte",
        "Votre compte est fermé et sera effacé le {1}. Pour le garder, connectez-vous à Kadans avant cette date.",
        "Ce lien est invalide ou a expiré.",
        ["janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre"],
        "{0} {1} {2}"
    );

    private static readonly DeletionTexts Creole = new(
        "Konfime sipresyon kont Kadans ou",
        "Bonjou {0}, yon moun mande pou efase kont Kadans sa a. Si se te ou, louvri lyen sa a pou konfime:\n{1}\n\nSi se pa t ou, pa okipe mesaj sa a: anyen pa chanje.",
        "Kont Kadans ou ap efase",
        "Bonjou {0}, kont Kadans ou fèmen, epi l ap efase ak tout sa ki ladan l (travay yo, istwa konsantrasyon, bidjè) {1}.\n\nOu chanje lide? Konekte nan Kadans anvan dat sa a epi chwazi kenbe l.\n\nEfase kont lan pa anile yon abònman ou te achte nan Google Play oswa App Store: anile l la.",
        "Kont Kadans ou efase",
        "Bonjou {0}, kont Kadans ou ak tout sa ki te ladan l efase, jan ou te mande a. Sovgad nou yo ap bliye l nan 14 jou.\n\nMèsi paske ou te itilize Kadans.",
        "Efase kont Kadans ou",
        "Antre adrès imèl kont ou: n ap voye yon lyen pou konfime. Kont ou fèmen touswit epi l ap efase ak tout sa ki ladan l (travay yo, istwa konsantrasyon, bidjè) 7 jou apre; konekte anvan sa pou kenbe l.",
        "Adrès imèl",
        "Voye lyen an",
        "Si yon kont sèvi ak adrès sa a, nou voye yon lyen pou konfime.",
        "Efase kont Kadans {0} an? Li fèmen touswit epi l ap efase ak tout sa ki ladan l {1}, sòf si ou konekte anvan sa pou kenbe l.",
        "Efase kont mwen",
        "Kont ou fèmen epi l ap efase {1}. Pou kenbe l, konekte nan Kadans anvan dat sa a.",
        "Lyen sa a pa valab oswa li ekspire.",
        ["janvye", "fevriye", "mas", "avril", "me", "jen", "jiyè", "out", "septanm", "oktòb", "novanm", "desanm"],
        "{0} {1} {2}"
    );
}
