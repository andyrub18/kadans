using Kadans.SharedKernel.Localization;

namespace Kadans.SharedKernel.Errors;

/// <summary>
/// French and Haitian Creole for everything an error response says. Three layers, tried in order:
/// <list type="number">
/// <item>the exact English sentence (<see cref="Sentences"/>) — every static message in the code base;</item>
/// <item>one sentence per <see cref="ErrorTypes"/> (<see cref="ByType"/>) — for messages that carry an id
///       or a name ("Todo with id … not found"), where the specifics help a developer, not a user;</item>
/// <item>the English text itself.</item>
/// </list>
/// Two tests keep this complete: every error type must have its sentence, and a scan of the source
/// fails when a static message has no translation here. English is never touched.
/// Passwords and usernames (ASP.NET Identity's messages, which carry numbers and names) are
/// localized in the Identity module's error describer instead.
/// </summary>
public static class ErrorTexts
{
    public sealed record Text(string Fr, string Ht)
    {
        public string In(string language) => language == "fr" ? Fr : Ht;
    }

    /// <summary>"Validation failed for creating user." — a developer's label; users get one plain sentence.</summary>
    private const string ValidationFailedPrefix = "Validation failed";

    public static string Localize(ErrorTypes type, string english, string language)
    {
        if (language is not ("fr" or "ht"))
            return english;
        if (english.StartsWith(ValidationFailedPrefix, StringComparison.Ordinal))
            return ByType[ErrorTypes.ValidationError.Value].In(language);
        if (Sentences.TryGetValue(english, out var sentence))
            return sentence.In(language);
        return ByType.TryGetValue(type.Value, out var generic) ? generic.In(language) : english;
    }

    /// <summary>One entry of a validation response; its code is an <see cref="ErrorTypes"/> value when ours, a name when Identity's.</summary>
    public static string LocalizeEntry(string code, string english, string language)
    {
        if (language is not ("fr" or "ht"))
            return english;
        if (Sentences.TryGetValue(english, out var sentence))
            return sentence.In(language);
        return ByType.TryGetValue(code, out var generic) ? generic.In(language) : english;
    }

    public static bool HasSentence(string english) =>
        english.StartsWith(ValidationFailedPrefix, StringComparison.Ordinal) || Sentences.ContainsKey(english);

    public static readonly IReadOnlyDictionary<string, Text> ByType = new Dictionary<string, Text>
    {
        ["10001"] = new("Vous devez être connecté.", "Ou dwe konekte."),
        ["10002"] = new("Vous n'avez pas le droit de faire cela.", "Ou pa gen dwa fè sa."),
        ["10003"] = new("Le mois n'est pas valide.", "Mwa a pa valab."),
        ["10004"] = new("La position dans la règle de répétition n'est pas valide.", "Pozisyon nan règ repetisyon an pa valab."),
        ["10005"] = new("C'est déjà terminé.", "Sa fini deja."),
        ["10007"] = new("La minute n'est pas valide.", "Minit la pa valab."),
        ["10008"] = new("L'heure n'est pas valide.", "Lè a pa valab."),
        ["10009"] = new("Le jour du mois n'est pas valide.", "Jou nan mwa a pa valab."),
        ["10010"] = new("L'intervalle n'est pas valide.", "Entèval la pa valab."),
        ["10011"] = new("La fréquence n'est pas valide.", "Frekans lan pa valab."),
        ["10012"] = new("La date de début n'est pas valide.", "Dat kòmansman an pa valab."),
        ["10013"] = new("La règle de répétition n'est pas valide.", "Règ repetisyon an pa valab."),
        ["10014"] = new("Il n'y a pas de prochaine occurrence.", "Pa gen pwochen fwa."),
        ["10015"] = new("Certaines informations ne sont pas valides.", "Gen kèk enfòmasyon ki pa valab."),
        ["10016"] = new("L'enregistrement a échoué. Réessayez.", "Anrejistreman an pa mache. Eseye ankò."),
        ["10017"] = new("Le titre est obligatoire.", "Tit la obligatwa."),
        ["10018"] = new("L'échéance n'est pas valide.", "Dat limit la pa valab."),
        ["10019"] = new("Tâche introuvable.", "Nou pa jwenn travay la."),
        ["10020"] = new("C'est déjà annulé.", "Sa anile deja."),
        ["10021"] = new("Occurrence introuvable.", "Nou pa jwenn fwa sa a."),
        ["10022"] = new("Utilisateur introuvable.", "Nou pa jwenn itilizatè a."),
        ["10023"] = new("Il n'y a aucune occurrence à venir à déplacer.", "Pa gen okenn fwa k ap vini pou deplase."),
        ["10024"] = new("Nom d'utilisateur ou mot de passe invalide.", "Non itilizatè oswa modpas la pa bon."),
        ["10025"] = new("Ce compte est désactivé.", "Kont sa a dezaktive."),
        ["10026"] = new("Cycle introuvable.", "Nou pa jwenn sik la."),
        ["10027"] = new("Ce cycle n'est pas valide.", "Sik sa a pa valab."),
        ["10028"] = new("Session introuvable.", "Nou pa jwenn sesyon an."),
        ["10029"] = new("La session n'est plus dans cet état. Actualisez et réessayez.", "Sesyon an pa nan eta sa a ankò. Aktyalize epi eseye ankò."),
        ["10030"] = new("Cette tâche a déjà une session en cours.", "Travay sa a gen yon sesyon k ap fèt deja."),
        ["10031"] = new("Cette tâche n'a pas de cycle Pomodoro.", "Travay sa a pa gen sik Pomodoro."),
        ["10032"] = new("Ce fuseau horaire n'est pas reconnu.", "Nou pa rekonèt zòn orè sa a."),
        ["10033"] = new("Ce lien ou ce code est invalide ou expiré.", "Lyen sa a oswa kòd sa a pa valab oswa li ekspire."),
        ["10034"] = new("Le code de vérification n'est pas valide.", "Kòd verifikasyon an pa valab."),
        ["10035"] = new("La connexion avec ce fournisseur n'a pas abouti.", "Koneksyon ak founisè sa a pa fin fèt."),
        ["10036"] = new("Cette méthode de connexion n'est pas configurée sur ce serveur.", "Metòd koneksyon sa a pa konfigire sou sèvè sa a."),
        ["10037"] = new("Appareil introuvable.", "Nou pa jwenn aparèy la."),
        ["10038"] = new("Cette adresse e-mail est déjà utilisée.", "Adrès imèl sa a deja itilize."),
        ["10039"] = new("L'authentification à deux facteurs est déjà activée.", "Otantifikasyon ak de faktè deja aktive."),
        ["10040"] = new("L'authentification à deux facteurs n'est pas activée.", "Otantifikasyon ak de faktè pa aktive."),
        ["10041"] = new("Ce compte n'a pas d'adresse e-mail.", "Kont sa a pa gen adrès imèl."),
        ["10042"] = new("Notification introuvable.", "Nou pa jwenn notifikasyon an."),
        ["10043"] = new("Compte introuvable.", "Nou pa jwenn kont lan."),
        ["10044"] = new("Catégorie introuvable.", "Nou pa jwenn kategori a."),
        ["10045"] = new("Mouvement introuvable.", "Nou pa jwenn mouvman an."),
        ["10046"] = new("Règle récurrente introuvable.", "Nou pa jwenn règ repetisyon an."),
        ["10047"] = new("Ce sont des devises différentes — indiquez le montant reçu.", "Se de lajan diferan — antre montan ou resevwa a."),
        ["10048"] = new("Ce montant n'est pas valide.", "Montan sa a pa valab."),
        ["10049"] = new("Un transfert nécessite deux comptes différents.", "Yon transfè bezwen de kont diferan."),
        ["10050"] = new("Cette catégorie ne correspond pas à ce type de mouvement.", "Kategori sa a pa mache ak kalite mouvman sa a."),
        ["10051"] = new("Ce compte est archivé.", "Kont sa a achive."),
        ["10052"] = new("Le délai du rappel n'est pas valide.", "Delè rapèl la pa valab."),
        ["10053"] = new("Trop de tentatives. Réessayez dans un moment.", "Twòp tantativ. Tann yon ti moman epi eseye ankò."),
        ["10054"] = new("Trop d'essais infructueux. Réessayez dans quelques minutes.", "Twòp tantativ ki pa mache. Eseye ankò nan kèk minit."),
        ["10055"] = new("Les abonnements ne sont pas encore disponibles.", "Abònman yo poko disponib."),
        ["10056"] = new("Cet achat n'a pas pu être vérifié.", "Nou pa t ka verifye acha sa a."),
        ["10057"] = new("Cet abonnement appartient à un autre compte Kadans.", "Abònman sa a se pou yon lòt kont Kadans."),
    };

    public static readonly IReadOnlyDictionary<string, Text> Sentences = new Dictionary<string, Text>(StringComparer.Ordinal)
    {
        // --- todos and recurrence ---
        ["Title is required."] = new("Le titre est obligatoire.", "Tit la obligatwa."),
        ["Due date must be in the future."] = new("L'échéance doit être dans le futur.", "Dat limit la dwe nan lavni."),
        ["Due date cannot be in the past."] = new("L'échéance ne peut pas être dans le passé.", "Dat limit la pa ka nan tan pase."),
        ["The new date must be in the future."] = new("La nouvelle date doit être dans le futur.", "Nouvo dat la dwe nan lavni."),
        ["Start date must not be in the past."] = new("La date de début ne doit pas être dans le passé.", "Dat kòmansman an pa dwe nan tan pase."),
        ["Start date cannot be in the past."] = new("La date de début ne peut pas être dans le passé.", "Dat kòmansman an pa ka nan tan pase."),
        ["Interval must be greater than zero."] = new("L'intervalle doit être supérieur à zéro.", "Entèval la dwe pi gran pase zewo."),
        ["Interval must be at least 1."] = new("L'intervalle doit être d'au moins 1.", "Entèval la dwe omwen 1."),
        ["Start date must be earlier than end date."] = new("La date de début doit précéder la date de fin.", "Dat kòmansman an dwe vini anvan dat fen an."),
        ["The stats range must be positive and at most a year."] = new("La période des statistiques doit être positive et d'un an au plus.", "Peryòd estatistik la dwe pozitif epi pa depase yon ane."),
        ["ByHour must contain at least one hour if specified."] = new("Indiquez au moins une heure.", "Mete omwen yon lè."),
        ["Hour must be between 0 and 23."] = new("L'heure doit être comprise entre 0 et 23.", "Lè a dwe ant 0 ak 23."),
        ["ByMinute must contain at least one minute if specified."] = new("Indiquez au moins une minute.", "Mete omwen yon minit."),
        ["Minute must be between 0 and 59."] = new("La minute doit être comprise entre 0 et 59.", "Minit la dwe ant 0 ak 59."),
        ["ByMonthDay must contain at least one day if specified."] = new("Indiquez au moins un jour du mois.", "Mete omwen yon jou nan mwa a."),
        ["Day of month must be between 1 and 31, or -1 and -31 to count from the end."] = new("Le jour du mois doit être entre 1 et 31, ou entre -1 et -31 pour compter depuis la fin.", "Jou nan mwa a dwe ant 1 ak 31, oswa ant -1 ak -31 pou konte depi nan fen an."),
        ["ByMonth must contain at least one month if specified."] = new("Indiquez au moins un mois.", "Mete omwen yon mwa."),
        ["Month must be between 1 and 12."] = new("Le mois doit être compris entre 1 et 12.", "Mwa a dwe ant 1 ak 12."),
        ["BySetPos must contain at least one position if specified."] = new("Indiquez au moins une position.", "Mete omwen yon pozisyon."),
        ["Set position must be non-zero, between -366 and 366."] = new("La position doit être non nulle, entre -366 et 366.", "Pozisyon an pa ka zewo; li dwe ant -366 ak 366."),
        ["BySetPos must be combined with ByDay or ByMonthDay."] = new("Une position s'utilise avec des jours de la semaine ou des jours du mois.", "Yon pozisyon mache ansanm ak jou nan semèn nan oswa jou nan mwa a."),
        ["TimeZone must be a valid IANA time zone id (e.g. America/Port-au-Prince)."] = new("Le fuseau horaire doit être un identifiant IANA valide (p. ex. America/Port-au-Prince).", "Zòn orè a dwe yon idantifyan IANA ki valab (pa egzanp America/Port-au-Prince)."),
        ["ByDayOfWeek must contain at least one day if specified."] = new("Indiquez au moins un jour de la semaine.", "Mete omwen yon jou nan semèn nan."),
        ["Count must be greater than zero if specified."] = new("Le nombre de fois doit être supérieur à zéro.", "Kantite fwa a dwe pi gran pase zewo."),
        ["Count must be at least 1."] = new("Le nombre de fois doit être d'au moins 1.", "Kantite fwa a dwe omwen 1."),
        ["Until date must be after start date if specified."] = new("La date de fin doit être après la date de début.", "Dat fen an dwe vini apre dat kòmansman an."),
        ["Start date must be before 'until' date."] = new("La date de début doit précéder la date de fin.", "Dat kòmansman an dwe vini anvan dat fen an."),
        ["Either Count or Until can be specified, but not both."] = new("Choisissez un nombre de fois ou une date de fin, pas les deux.", "Chwazi yon kantite fwa oswa yon dat fen, pa toude."),
        ["Cannot specify both 'until' and 'count'. They are mutually exclusive."] = new("Choisissez un nombre de fois ou une date de fin, pas les deux.", "Chwazi yon kantite fwa oswa yon dat fen, pa toude."),
        ["Failed to create todo."] = new("La tâche n'a pas pu être créée. Réessayez.", "Nou pa rive kreye travay la. Eseye ankò."),
        ["A reminder can come at most 30 days before the start."] = new("Un rappel peut arriver au plus 30 jours avant le début.", "Yon rapèl ka vini 30 jou anvan kòmansman an pou pi plis."),
        ["A rule can repeat at most 5,000 times."] = new("Une règle peut se répéter au plus 5 000 fois.", "Yon règ ka repete 5 000 fwa pou pi plis."),
        ["A rule can run for at most 10 years."] = new("Une règle peut durer au plus 10 ans.", "Yon règ ka dire 10 an pou pi plis."),
        ["A rule can repeat at most every 5 minutes."] = new("Une règle ne peut pas se répéter plus souvent que toutes les 5 minutes.", "Yon règ pa ka repete pi souvan pase chak 5 minit."),
        ["A rule can fire at most 288 times a day."] = new("Une règle peut se déclencher au plus 288 fois par jour.", "Yon règ ka fèt 288 fwa pa jou pou pi plis."),
        ["An hourly or minute rule cannot be limited to certain hours, days or months."] = new("Une règle à l'heure ou à la minute ne peut pas être limitée à certaines heures, certains jours ou certains mois.", "Yon règ chak èdtan oswa chak minit pa ka limite a sèten lè, sèten jou oswa sèten mwa."),
        ["The calendar range can be at most a year."] = new("La période du calendrier peut couvrir au plus un an.", "Peryòd kalandriye a ka kouvri yon ane pou pi plis."),
        // --- pomodoro ---
        ["Only active or paused runs can be cancelled."] = new("Seule une session en cours ou en pause peut être arrêtée.", "Se sèlman yon sesyon k ap fèt oswa ki an poz ou ka kanpe."),
        ["Only active or paused runs can be finished."] = new("Seule une session en cours ou en pause peut être terminée.", "Se sèlman yon sesyon k ap fèt oswa ki an poz ou ka fini."),
        ["Only active runs can advance phases."] = new("Seule une session en cours peut changer de phase.", "Se sèlman yon sesyon k ap fèt ki ka chanje faz."),
        ["Only active runs can be paused."] = new("Seule une session en cours peut être mise en pause.", "Se sèlman yon sesyon k ap fèt ou ka mete an poz."),
        ["Only paused runs can be resumed."] = new("Seule une session en pause peut être reprise.", "Se sèlman yon sesyon ki an poz ou ka rekòmanse."),
        ["Run phase index mismatch. Refresh run state and retry."] = new("La session a changé de phase entre-temps. Actualisez et réessayez.", "Sesyon an chanje faz pandan tan sa a. Aktyalize epi eseye ankò."),
        ["The run changed at the same moment. Refresh run state and retry."] = new("La session a changé au même moment. Actualisez et réessayez.", "Sesyon an chanje nan menm moman an. Aktyalize epi eseye ankò."),
        ["This todo already has an active Pomodoro run."] = new("Cette tâche a déjà une session en cours.", "Travay sa a gen yon sesyon k ap fèt deja."),
        ["No active Pomodoro run found for this todo."] = new("Cette tâche n'a aucune session en cours.", "Travay sa a pa gen okenn sesyon k ap fèt."),
        ["At least one Pomodoro phase is required."] = new("Il faut au moins une phase.", "Fòk gen omwen yon faz."),
        ["Cannot attach an empty Pomodoro template."] = new("Un cycle vide ne peut pas être attaché.", "Ou pa ka tache yon sik ki vid."),
        ["Cannot start a Pomodoro run from an empty template."] = new("Impossible de démarrer une session avec un cycle vide.", "Ou pa ka kòmanse yon sesyon ak yon sik ki vid."),
        ["Phase duration must be greater than zero."] = new("La durée d'une phase doit être supérieure à zéro.", "Dire yon faz dwe pi gran pase zewo."),
        ["Only active or paused runs can change their end."] = new("Seule une session active ou en pause peut changer son heure de fin.", "Se sèlman yon seyans k ap mache oswa ki an poz ki ka chanje lè li fini."),
        ["Each phase lasts 1 to 240 minutes."] = new("Chaque phase dure de 1 à 240 minutes.", "Chak etap dire ant 1 ak 240 minit."),
        ["A cycle has at most 24 phases."] = new("Un cycle compte au plus 24 phases.", "Yon sik gen 24 etap pou pi plis."),
        ["A session ends between 1 minute and 24 hours from now."] = new("Une session se termine entre 1 minute et 24 heures à partir de maintenant.", "Yon seyans fini ant 1 minit ak 24 èdtan apati kounye a."),
        ["Template name is required."] = new("Le nom du cycle est obligatoire.", "Non sik la obligatwa."),
        ["This todo has no Pomodoro template attached."] = new("Cette tâche n'a pas de cycle Pomodoro.", "Travay sa a pa gen sik Pomodoro."),
        // --- identity ---
        ["Too many attempts. Try again in a moment."] = new("Trop de tentatives. Réessayez dans un moment.", "Twòp tantativ. Tann yon ti moman epi eseye ankò."),
        ["Invalid username or password"] = new("Nom d'utilisateur ou mot de passe invalide.", "Non itilizatè oswa modpas la pa bon."),
        ["Invalid refresh token"] = new("Votre session a expiré. Reconnectez-vous.", "Sesyon ou an ekspire. Konekte ankò."),
        ["The current password is not correct."] = new("Le mot de passe actuel n'est pas correct.", "Modpas aktyèl la pa bon."),
        ["This account has no email address."] = new("Ce compte n'a pas d'adresse e-mail.", "Kont sa a pa gen adrès imèl."),
        ["This offer to keep the account has expired. Sign in again."] = new("Cette offre de garder le compte a expiré. Reconnectez-vous.", "Òf pou kenbe kont lan ekspire. Konekte ankò."),
        ["Subscriptions are not available yet."] = new("Les abonnements ne sont pas encore disponibles.", "Abònman yo poko disponib."),
        ["This purchase could not be verified."] = new("Cet achat n'a pas pu être vérifié.", "Nou pa t ka verifye acha sa a."),
        ["This subscription belongs to another Kadans account."] = new("Cet abonnement appartient à un autre compte Kadans.", "Abònman sa a se pou yon lòt kont Kadans."),
        ["No account has that username or confirmed email address."] = new("Aucun compte n'a ce nom d'utilisateur ou cette adresse e-mail confirmée.", "Pa gen okenn kont ki gen non itilizatè sa a oswa adrès imel konfime sa a."),
        ["Enter your current password."] = new("Saisissez votre mot de passe actuel.", "Antre modpas aktyèl ou."),
        ["Too many failed attempts. Try again in a few minutes."] = new("Trop d'essais infructueux. Réessayez dans quelques minutes.", "Twòp tantativ ki pa mache. Eseye ankò nan kèk minit."),
        ["A username can contain @ only when it is the account's email address."] = new("Un nom d'utilisateur ne peut contenir @ que s'il est l'adresse e-mail du compte.", "Yon non itilizatè ka gen @ sèlman si se adrès imèl kont lan."),
        ["Page must be 1 or more."] = new("La page doit être 1 ou plus.", "Paj la dwe 1 oswa plis."),
        ["Page size must be between 1 and 100."] = new("La taille de page doit être comprise entre 1 et 100.", "Gwosè paj la dwe ant 1 ak 100."),
        ["User is deactivated"] = new("Ce compte est désactivé.", "Kont sa a dezaktive."),
        ["Current user no longer exists."] = new("Ce compte n'existe plus.", "Kont sa a pa egziste ankò."),
        ["Unable to resolve current user."] = new("Vous devez être connecté.", "Ou dwe konekte."),
        ["User is not authenticated."] = new("Vous devez être connecté.", "Ou dwe konekte."),
        ["User must be authenticated to create a todo."] = new("Vous devez être connecté pour créer une tâche.", "Ou dwe konekte pou kreye yon travay."),
        ["This email address is already in use."] = new("Cette adresse e-mail est déjà utilisée.", "Adrès imèl sa a deja itilize."),
        ["The confirmation link is invalid or expired."] = new("Le lien de confirmation est invalide ou expiré.", "Lyen konfimasyon an pa valab oswa li ekspire."),
        ["The reset link is invalid or expired."] = new("Le lien de réinitialisation est invalide ou expiré.", "Lyen reyinisyalizasyon an pa valab oswa li ekspire."),
        ["The MFA token is invalid or expired."] = new("La vérification a expiré. Reconnectez-vous.", "Verifikasyon an ekspire. Konekte ankò."),
        ["The verification code is not valid."] = new("Le code de vérification n'est pas valide.", "Kòd verifikasyon an pa valab."),
        ["Two-factor authentication is already enabled."] = new("L'authentification à deux facteurs est déjà activée.", "Otantifikasyon ak de faktè deja aktive."),
        ["Disable two-factor authentication before enrolling again."] = new("Désactivez l'authentification à deux facteurs avant de la configurer à nouveau.", "Dezaktive otantifikasyon ak de faktè anvan ou konfigire l ankò."),
        ["Two-factor authentication is not enabled."] = new("L'authentification à deux facteurs n'est pas activée.", "Otantifikasyon ak de faktè pa aktive."),
        ["Name is required."] = new("Le nom est obligatoire.", "Non an obligatwa."),
        ["One or more roles are invalid."] = new("Un ou plusieurs rôles ne sont pas valides.", "Gen youn oswa plizyè wòl ki pa valab."),
        ["Only admins can assign roles."] = new("Seuls les administrateurs peuvent attribuer des rôles.", "Se sèlman administratè yo ki ka bay wòl."),
        ["Only admins can create users via this endpoint."] = new("Seuls les administrateurs peuvent créer des utilisateurs ici.", "Se sèlman administratè yo ki ka kreye itilizatè la a."),
        ["Only admins can deactivate other users."] = new("Seuls les administrateurs peuvent désactiver d'autres utilisateurs.", "Se sèlman administratè yo ki ka dezaktive lòt itilizatè."),
        ["Only admins can update other users."] = new("Seuls les administrateurs peuvent modifier d'autres utilisateurs.", "Se sèlman administratè yo ki ka modifye lòt itilizatè."),
        ["Only admins can update roles."] = new("Seuls les administrateurs peuvent modifier les rôles.", "Se sèlman administratè yo ki ka modifye wòl yo."),
        // --- external sign-in ---
        ["An authorization code, its PKCE verifier and a loopback redirect URI are required."] = new("La connexion avec Google n'a pas abouti. Réessayez.", "Koneksyon ak Google la pa fin fèt. Eseye ankò."),
        ["Could not create an account from this login."] = new("Impossible de créer un compte à partir de cette connexion.", "Nou pa rive kreye yon kont ak koneksyon sa a."),
        ["Could not link this login to the account."] = new("Impossible d'associer cette connexion au compte.", "Nou pa rive mare koneksyon sa a ak kont lan."),
        ["This sign-in did not come with a verified email address."] = new("Cette connexion n'a pas fourni d'adresse e-mail vérifiée.", "Koneksyon sa a pa bay okenn adrès imèl ki verifye."),
        ["Could not reach Google to complete the sign-in."] = new("Impossible de joindre Google pour terminer la connexion.", "Nou pa rive jwenn Google pou fini koneksyon an."),
        ["Google did not accept the sign-in code."] = new("Google n'a pas accepté la connexion. Réessayez.", "Google pa aksepte koneksyon an. Eseye ankò."),
        ["Google returned no ID token."] = new("La connexion avec Google n'a pas abouti. Réessayez.", "Koneksyon ak Google la pa fin fèt. Eseye ankò."),
        ["The ID token has no subject."] = new("La connexion n'a pas abouti. Réessayez.", "Koneksyon an pa fin fèt. Eseye ankò."),
        ["The ID token is not valid."] = new("La connexion n'a pas abouti. Réessayez.", "Koneksyon an pa fin fèt. Eseye ankò."),
        ["Google desktop sign-in is not configured (ExternalAuth:Google:Desktop:ClientId / ClientSecret)."] = new("La connexion avec Google n'est pas configurée sur ce serveur.", "Koneksyon ak Google pa konfigire sou sèvè sa a."),
        // --- budget ---
        ["Account name is required."] = new("Le nom du compte est obligatoire.", "Non kont lan obligatwa."),
        ["Category name is required."] = new("Le nom de la catégorie est obligatoire.", "Non kategori a obligatwa."),
        ["Provide a real year and month."] = new("Indiquez une année et un mois valides.", "Mete yon ane ak yon mwa ki valab."),
        ["Recurring transfers are not supported yet."] = new("Les transferts récurrents ne sont pas encore pris en charge.", "Transfè ki repete poko disponib."),
        ["A recurring transaction can repeat at most once a day."] = new("Un mouvement récurrent peut se répéter au plus une fois par jour.", "Yon mouvman ki repete ka fèt yon fwa pa jou pou pi plis."),
        ["A recurring transaction can start at most a year ago."] = new("Un mouvement récurrent peut commencer il y a un an au plus.", "Yon mouvman ki repete ka kòmanse yon ane pase pou pi plis."),
        ["The destination account is archived."] = new("Le compte de destination est archivé.", "Kont destinasyon an achive."),
        ["This account is archived."] = new("Ce compte est archivé.", "Kont sa a achive."),
        ["Budgets apply to expense categories."] = new("Les budgets s'appliquent aux catégories de dépenses.", "Bidjè yo se pou kategori depans sèlman."),
        ["Transfers have no category."] = new("Un transfert n'a pas de catégorie.", "Yon transfè pa gen kategori."),
        ["Cross-currency transfers need the received amount."] = new("Un transfert entre deux devises nécessite le montant reçu.", "Yon transfè ant de lajan diferan bezwen montan ou resevwa a."),
        ["Same-currency transfers move one single amount."] = new("Un transfert dans la même devise ne porte qu'un seul montant.", "Yon transfè nan menm lajan an gen yon sèl montan."),
        ["The base currency needs no rate — it is worth 1 of itself."] = new("La devise de base n'a pas besoin de taux : elle vaut 1.", "Lajan de baz la pa bezwen to: li vo 1."),
        ["Amount can have at most two decimals."] = new("Le montant peut avoir au plus deux décimales.", "Montan an ka gen de chif apre vigil la pou pi plis."),
        ["Amount is out of range."] = new("Le montant est hors limites.", "Montan an depase limit yo."),
        ["Amount must be greater than zero."] = new("Le montant doit être supérieur à zéro.", "Montan an dwe pi gran pase zewo."),
        ["Initial balance can have at most two decimals."] = new("Le solde initial peut avoir au plus deux décimales.", "Balans kòmansman an ka gen de chif apre vigil la pou pi plis."),
        ["The rate can have at most six decimals."] = new("Le taux peut avoir au plus six décimales.", "To a ka gen sis chif apre vigil la pou pi plis."),
        ["The rate must be positive."] = new("Le taux doit être positif.", "To a dwe pozitif."),
        ["A transfer needs a destination account."] = new("Un transfert nécessite un compte de destination.", "Yon transfè bezwen yon kont destinasyon."),
        ["A transfer needs two different accounts."] = new("Un transfert nécessite deux comptes différents.", "Yon transfè bezwen de kont diferan."),
        ["Only transfers involve a second account."] = new("Seul un transfert utilise un second compte.", "Se sèlman yon transfè ki sèvi ak yon dezyèm kont."),
    };
}
