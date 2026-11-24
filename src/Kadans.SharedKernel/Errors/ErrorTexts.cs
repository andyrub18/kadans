using Kadans.SharedKernel.Localization;

namespace Kadans.SharedKernel.Errors;

/// <summary>
/// French, Haitian Creole and Spanish for everything an error response says. Three layers, tried in order:
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
    public sealed record Text(string Fr, string Ht, string Es)
    {
        public string In(string language) =>
            language switch
            {
                "fr" => Fr,
                "ht" => Ht,
                _ => Es,
            };
    }

    /// <summary>English is the text the code wrote: only the other supported languages are looked up.</summary>
    private static bool IsTranslated(string language) => language is "fr" or "ht" or "es";

    /// <summary>"Validation failed for creating user." — a developer's label; users get one plain sentence.</summary>
    private const string ValidationFailedPrefix = "Validation failed";

    public static string Localize(ErrorTypes type, string english, string language)
    {
        if (!IsTranslated(language))
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
        if (!IsTranslated(language))
            return english;
        if (Sentences.TryGetValue(english, out var sentence))
            return sentence.In(language);
        return ByType.TryGetValue(code, out var generic) ? generic.In(language) : english;
    }

    public static bool HasSentence(string english) =>
        english.StartsWith(ValidationFailedPrefix, StringComparison.Ordinal) || Sentences.ContainsKey(english);

    public static readonly IReadOnlyDictionary<string, Text> ByType = new Dictionary<string, Text>
    {
        ["10001"] = new("Vous devez être connecté.", "Ou dwe konekte.", "Debes iniciar sesión."),
        ["10002"] = new("Vous n'avez pas le droit de faire cela.", "Ou pa gen dwa fè sa.", "No tienes permiso para hacer eso."),
        ["10003"] = new("Le mois n'est pas valide.", "Mwa a pa valab.", "El mes no es válido."),
        ["10004"] = new("La position dans la règle de répétition n'est pas valide.", "Pozisyon nan règ repetisyon an pa valab.", "La posición en la regla de repetición no es válida."),
        ["10005"] = new("C'est déjà terminé.", "Sa fini deja.", "Ya se completó."),
        ["10007"] = new("La minute n'est pas valide.", "Minit la pa valab.", "El minuto no es válido."),
        ["10008"] = new("L'heure n'est pas valide.", "Lè a pa valab.", "La hora no es válida."),
        ["10009"] = new("Le jour du mois n'est pas valide.", "Jou nan mwa a pa valab.", "El día del mes no es válido."),
        ["10010"] = new("L'intervalle n'est pas valide.", "Entèval la pa valab.", "El intervalo no es válido."),
        ["10011"] = new("La fréquence n'est pas valide.", "Frekans lan pa valab.", "La frecuencia no es válida."),
        ["10012"] = new("La date de début n'est pas valide.", "Dat kòmansman an pa valab.", "La fecha de inicio no es válida."),
        ["10013"] = new("La règle de répétition n'est pas valide.", "Règ repetisyon an pa valab.", "La regla de repetición no es válida."),
        ["10014"] = new("Il n'y a pas de prochaine occurrence.", "Pa gen pwochen fwa.", "No hay una próxima repetición."),
        ["10015"] = new("Certaines informations ne sont pas valides.", "Gen kèk enfòmasyon ki pa valab.", "Algunos datos no son válidos."),
        ["10016"] = new("L'enregistrement a échoué. Réessayez.", "Anrejistreman an pa mache. Eseye ankò.", "No se pudo guardar. Inténtalo de nuevo."),
        ["10017"] = new("Le titre est obligatoire.", "Tit la obligatwa.", "El título es obligatorio."),
        ["10018"] = new("L'échéance n'est pas valide.", "Dat limit la pa valab.", "La fecha límite no es válida."),
        ["10019"] = new("Tâche introuvable.", "Nou pa jwenn travay la.", "No se encontró la tarea."),
        ["10020"] = new("C'est déjà annulé.", "Sa anile deja.", "Ya se canceló."),
        ["10021"] = new("Occurrence introuvable.", "Nou pa jwenn fwa sa a.", "No se encontró la repetición."),
        ["10022"] = new("Utilisateur introuvable.", "Nou pa jwenn itilizatè a.", "No se encontró el usuario."),
        ["10023"] = new("Il n'y a aucune occurrence à venir à déplacer.", "Pa gen okenn fwa k ap vini pou deplase.", "No hay ninguna repetición próxima para mover."),
        ["10024"] = new("Nom d'utilisateur ou mot de passe invalide.", "Non itilizatè oswa modpas la pa bon.", "Nombre de usuario o contraseña incorrectos."),
        ["10025"] = new("Ce compte est désactivé.", "Kont sa a dezaktive.", "Esta cuenta está desactivada."),
        ["10026"] = new("Cycle introuvable.", "Nou pa jwenn sik la.", "No se encontró el ciclo."),
        ["10027"] = new("Ce cycle n'est pas valide.", "Sik sa a pa valab.", "Este ciclo no es válido."),
        ["10028"] = new("Session introuvable.", "Nou pa jwenn sesyon an.", "No se encontró la sesión."),
        ["10029"] = new("La session n'est plus dans cet état. Actualisez et réessayez.", "Sesyon an pa nan eta sa a ankò. Aktyalize epi eseye ankò.", "La sesión ya no está en ese estado. Actualiza e inténtalo de nuevo."),
        ["10030"] = new("Cette tâche a déjà une session en cours.", "Travay sa a gen yon sesyon k ap fèt deja.", "Esta tarea ya tiene una sesión en curso."),
        ["10031"] = new("Cette tâche n'a pas de cycle Pomodoro.", "Travay sa a pa gen sik Pomodoro.", "Esta tarea no tiene un ciclo Pomodoro."),
        ["10032"] = new("Ce fuseau horaire n'est pas reconnu.", "Nou pa rekonèt zòn orè sa a.", "No se reconoce esta zona horaria."),
        ["10033"] = new("Ce lien ou ce code est invalide ou expiré.", "Lyen sa a oswa kòd sa a pa valab oswa li ekspire.", "Este enlace o este código no es válido o ya expiró."),
        ["10034"] = new("Le code de vérification n'est pas valide.", "Kòd verifikasyon an pa valab.", "El código de verificación no es válido."),
        ["10035"] = new("La connexion avec ce fournisseur n'a pas abouti.", "Koneksyon ak founisè sa a pa fin fèt.", "No se pudo iniciar sesión con este proveedor."),
        ["10036"] = new("Cette méthode de connexion n'est pas configurée sur ce serveur.", "Metòd koneksyon sa a pa konfigire sou sèvè sa a.", "Este método de inicio de sesión no está configurado en este servidor."),
        ["10037"] = new("Appareil introuvable.", "Nou pa jwenn aparèy la.", "No se encontró el dispositivo."),
        ["10038"] = new("Cette adresse e-mail est déjà utilisée.", "Adrès imèl sa a deja itilize.", "Este correo electrónico ya está en uso."),
        ["10039"] = new("L'authentification à deux facteurs est déjà activée.", "Otantifikasyon ak de faktè deja aktive.", "La autenticación de dos factores ya está activada."),
        ["10040"] = new("L'authentification à deux facteurs n'est pas activée.", "Otantifikasyon ak de faktè pa aktive.", "La autenticación de dos factores no está activada."),
        ["10041"] = new("Ce compte n'a pas d'adresse e-mail.", "Kont sa a pa gen adrès imèl.", "Esta cuenta no tiene correo electrónico."),
        ["10042"] = new("Notification introuvable.", "Nou pa jwenn notifikasyon an.", "No se encontró la notificación."),
        ["10043"] = new("Compte introuvable.", "Nou pa jwenn kont lan.", "No se encontró la cuenta."),
        ["10044"] = new("Catégorie introuvable.", "Nou pa jwenn kategori a.", "No se encontró la categoría."),
        ["10045"] = new("Mouvement introuvable.", "Nou pa jwenn mouvman an.", "No se encontró el movimiento."),
        ["10046"] = new("Règle récurrente introuvable.", "Nou pa jwenn règ repetisyon an.", "No se encontró el movimiento recurrente."),
        ["10047"] = new("Ce sont des devises différentes — indiquez le montant reçu.", "Se de lajan diferan — antre montan ou resevwa a.", "Son monedas diferentes: indica el monto recibido."),
        ["10048"] = new("Ce montant n'est pas valide.", "Montan sa a pa valab.", "Este monto no es válido."),
        ["10049"] = new("Un transfert nécessite deux comptes différents.", "Yon transfè bezwen de kont diferan.", "Una transferencia necesita dos cuentas diferentes."),
        ["10050"] = new("Cette catégorie ne correspond pas à ce type de mouvement.", "Kategori sa a pa mache ak kalite mouvman sa a.", "Esta categoría no corresponde a este tipo de movimiento."),
        ["10051"] = new("Ce compte est archivé.", "Kont sa a achive.", "Esta cuenta está archivada."),
        ["10052"] = new("Le délai du rappel n'est pas valide.", "Delè rapèl la pa valab.", "La anticipación del recordatorio no es válida."),
        ["10053"] = new("Trop de tentatives. Réessayez dans un moment.", "Twòp tantativ. Tann yon ti moman epi eseye ankò.", "Demasiados intentos. Inténtalo de nuevo en un momento."),
        ["10054"] = new("Trop d'essais infructueux. Réessayez dans quelques minutes.", "Twòp tantativ ki pa mache. Eseye ankò nan kèk minit.", "Demasiados intentos fallidos. Inténtalo de nuevo en unos minutos."),
        ["10055"] = new("Les abonnements ne sont pas encore disponibles.", "Abònman yo poko disponib.", "Las suscripciones aún no están disponibles."),
        ["10056"] = new("Cet achat n'a pas pu être vérifié.", "Nou pa t ka verifye acha sa a.", "No se pudo verificar esta compra."),
        ["10057"] = new("Cet abonnement appartient à un autre compte Kadans.", "Abònman sa a se pou yon lòt kont Kadans.", "Esta suscripción pertenece a otra cuenta de Kadans."),
        ["10058"] = new("Le serveur est surchargé. Réessayez dans un moment.", "Sèvè a twò chaje kounye a. Tann yon ti moman epi eseye ankò.", "El servidor está ocupado. Inténtalo de nuevo en un momento."),
        ["10059"] = new("Cela fait partie de l'abonnement du téléphone.", "Sa fè pati abònman telefòn nan.", "Esto forma parte de la suscripción del teléfono."),
        ["10060"] = new("Ce texte est trop long.", "Tèks sa a twò long.", "Este texto es demasiado largo."),
    };

    public static readonly IReadOnlyDictionary<string, Text> Sentences = new Dictionary<string, Text>(StringComparer.Ordinal)
    {
        // --- todos and recurrence ---
        ["Title is required."] = new("Le titre est obligatoire.", "Tit la obligatwa.", "El título es obligatorio."),
        ["There is nothing to import."] = new("Il n'y a rien à importer.", "Pa gen anyen pou enpòte.", "No hay nada que importar."),
        ["At most 500 lines can be imported at once."] = new("On peut importer 500 lignes au plus à la fois.", "Ou ka enpòte 500 liy pou pi plis yon sèl kou.", "Se pueden importar 500 líneas como máximo a la vez."),
        ["A statement's line is missing what names it."] = new("Il manque à une ligne du relevé ce qui la désigne.", "Gen yon liy nan relve a ki manke sa ki idantifye l.", "A una línea del estado de cuenta le falta lo que la identifica."),
        ["A statement's lines are income or expenses, not transfers."] = new("Les lignes d'un relevé sont des revenus ou des dépenses, pas des transferts.", "Liy yon relve se revni oswa depans, se pa transfè.", "Las líneas de un estado de cuenta son ingresos o gastos, no transferencias."),
        ["This statement is being imported from somewhere else. Try again in a moment."] = new("Ce relevé est en cours d'import ailleurs. Réessayez dans un moment.", "Y ap enpòte relve sa a yon lòt kote. Eseye ankò talè.", "Este estado de cuenta se está importando desde otro lugar. Inténtalo de nuevo en un momento."),
        ["A title can have at most 500 characters."] = new("Un titre peut avoir 500 caractères au plus.", "Yon tit ka gen 500 karaktè pou pi plis.", "Un título puede tener 500 caracteres como máximo."),
        ["A description can have at most 4,000 characters."] = new("Une description peut avoir 4 000 caractères au plus.", "Yon deskripsyon ka gen 4 000 karaktè pou pi plis.", "Una descripción puede tener 4 000 caracteres como máximo."),
        ["Due date must be in the future."] = new("L'échéance doit être dans le futur.", "Dat limit la dwe nan lavni.", "La fecha límite debe estar en el futuro."),
        ["Due date cannot be in the past."] = new("L'échéance ne peut pas être dans le passé.", "Dat limit la pa ka nan tan pase.", "La fecha límite no puede estar en el pasado."),
        ["The new date must be in the future."] = new("La nouvelle date doit être dans le futur.", "Nouvo dat la dwe nan lavni.", "La nueva fecha debe estar en el futuro."),
        ["Start date must not be in the past."] = new("La date de début ne doit pas être dans le passé.", "Dat kòmansman an pa dwe nan tan pase.", "La fecha de inicio no debe estar en el pasado."),
        ["Start date cannot be in the past."] = new("La date de début ne peut pas être dans le passé.", "Dat kòmansman an pa ka nan tan pase.", "La fecha de inicio no puede estar en el pasado."),
        ["Interval must be greater than zero."] = new("L'intervalle doit être supérieur à zéro.", "Entèval la dwe pi gran pase zewo.", "El intervalo debe ser mayor que cero."),
        ["Interval must be at least 1."] = new("L'intervalle doit être d'au moins 1.", "Entèval la dwe omwen 1.", "El intervalo debe ser de al menos 1."),
        ["Start date must be earlier than end date."] = new("La date de début doit précéder la date de fin.", "Dat kòmansman an dwe vini anvan dat fen an.", "La fecha de inicio debe ser anterior a la fecha de fin."),
        ["The stats range must be positive and at most a year."] = new("La période des statistiques doit être positive et d'un an au plus.", "Peryòd estatistik la dwe pozitif epi pa depase yon ane.", "El período de las estadísticas debe ser positivo y de un año como máximo."),
        ["ByHour must contain at least one hour if specified."] = new("Indiquez au moins une heure.", "Mete omwen yon lè.", "Indica al menos una hora."),
        ["Hour must be between 0 and 23."] = new("L'heure doit être comprise entre 0 et 23.", "Lè a dwe ant 0 ak 23.", "La hora debe estar entre 0 y 23."),
        ["ByMinute must contain at least one minute if specified."] = new("Indiquez au moins une minute.", "Mete omwen yon minit.", "Indica al menos un minuto."),
        ["Minute must be between 0 and 59."] = new("La minute doit être comprise entre 0 et 59.", "Minit la dwe ant 0 ak 59.", "El minuto debe estar entre 0 y 59."),
        ["ByMonthDay must contain at least one day if specified."] = new("Indiquez au moins un jour du mois.", "Mete omwen yon jou nan mwa a.", "Indica al menos un día del mes."),
        ["Day of month must be between 1 and 31, or -1 and -31 to count from the end."] = new("Le jour du mois doit être entre 1 et 31, ou entre -1 et -31 pour compter depuis la fin.", "Jou nan mwa a dwe ant 1 ak 31, oswa ant -1 ak -31 pou konte depi nan fen an.", "El día del mes debe estar entre 1 y 31, o entre -1 y -31 para contar desde el final."),
        ["ByMonth must contain at least one month if specified."] = new("Indiquez au moins un mois.", "Mete omwen yon mwa.", "Indica al menos un mes."),
        ["Month must be between 1 and 12."] = new("Le mois doit être compris entre 1 et 12.", "Mwa a dwe ant 1 ak 12.", "El mes debe estar entre 1 y 12."),
        ["BySetPos must contain at least one position if specified."] = new("Indiquez au moins une position.", "Mete omwen yon pozisyon.", "Indica al menos una posición."),
        ["Set position must be non-zero, between -366 and 366."] = new("La position doit être non nulle, entre -366 et 366.", "Pozisyon an pa ka zewo; li dwe ant -366 ak 366.", "La posición no puede ser cero; debe estar entre -366 y 366."),
        ["BySetPos must be combined with ByDay or ByMonthDay."] = new("Une position s'utilise avec des jours de la semaine ou des jours du mois.", "Yon pozisyon mache ansanm ak jou nan semèn nan oswa jou nan mwa a.", "Una posición se usa con días de la semana o días del mes."),
        ["TimeZone must be a valid IANA time zone id (e.g. America/Port-au-Prince)."] = new("Le fuseau horaire doit être un identifiant IANA valide (p. ex. America/Port-au-Prince).", "Zòn orè a dwe yon idantifyan IANA ki valab (pa egzanp America/Port-au-Prince).", "La zona horaria debe ser un identificador IANA válido (p. ej., America/Port-au-Prince)."),
        ["ByDayOfWeek must contain at least one day if specified."] = new("Indiquez au moins un jour de la semaine.", "Mete omwen yon jou nan semèn nan.", "Indica al menos un día de la semana."),
        ["Count must be greater than zero if specified."] = new("Le nombre de fois doit être supérieur à zéro.", "Kantite fwa a dwe pi gran pase zewo.", "El número de veces debe ser mayor que cero."),
        ["Count must be at least 1."] = new("Le nombre de fois doit être d'au moins 1.", "Kantite fwa a dwe omwen 1.", "El número de veces debe ser de al menos 1."),
        ["Until date must be after start date if specified."] = new("La date de fin doit être après la date de début.", "Dat fen an dwe vini apre dat kòmansman an.", "La fecha de fin debe ser posterior a la fecha de inicio."),
        ["Start date must be before 'until' date."] = new("La date de début doit précéder la date de fin.", "Dat kòmansman an dwe vini anvan dat fen an.", "La fecha de inicio debe ser anterior a la fecha de fin."),
        ["Either Count or Until can be specified, but not both."] = new("Choisissez un nombre de fois ou une date de fin, pas les deux.", "Chwazi yon kantite fwa oswa yon dat fen, pa toude.", "Elige un número de veces o una fecha de fin, no ambos."),
        ["Cannot specify both 'until' and 'count'. They are mutually exclusive."] = new("Choisissez un nombre de fois ou une date de fin, pas les deux.", "Chwazi yon kantite fwa oswa yon dat fen, pa toude.", "Elige un número de veces o una fecha de fin, no ambos."),
        ["Failed to create todo."] = new("La tâche n'a pas pu être créée. Réessayez.", "Nou pa rive kreye travay la. Eseye ankò.", "No se pudo crear la tarea. Inténtalo de nuevo."),
        ["A reminder can come at most 30 days before the start."] = new("Un rappel peut arriver au plus 30 jours avant le début.", "Yon rapèl ka vini 30 jou anvan kòmansman an pou pi plis.", "Un recordatorio puede llegar como máximo 30 días antes del inicio."),
        ["A rule can repeat at most 5,000 times."] = new("Une règle peut se répéter au plus 5 000 fois.", "Yon règ ka repete 5 000 fwa pou pi plis.", "Una regla puede repetirse 5 000 veces como máximo."),
        ["A rule can run for at most 10 years."] = new("Une règle peut durer au plus 10 ans.", "Yon règ ka dire 10 an pou pi plis.", "Una regla puede durar 10 años como máximo."),
        ["A rule can repeat at most every 5 minutes."] = new("Une règle ne peut pas se répéter plus souvent que toutes les 5 minutes.", "Yon règ pa ka repete pi souvan pase chak 5 minit.", "Una regla no puede repetirse con más frecuencia que cada 5 minutos."),
        ["A rule can fire at most 288 times a day."] = new("Une règle peut se déclencher au plus 288 fois par jour.", "Yon règ ka fèt 288 fwa pa jou pou pi plis.", "Una regla puede activarse 288 veces al día como máximo."),
        ["An hourly or minute rule cannot be limited to certain hours, days or months."] = new("Une règle à l'heure ou à la minute ne peut pas être limitée à certaines heures, certains jours ou certains mois.", "Yon règ chak èdtan oswa chak minit pa ka limite a sèten lè, sèten jou oswa sèten mwa.", "Una regla por hora o por minuto no puede limitarse a ciertas horas, días o meses."),
        ["The calendar range can be at most a year."] = new("La période du calendrier peut couvrir au plus un an.", "Peryòd kalandriye a ka kouvri yon ane pou pi plis.", "El período del calendario puede abarcar un año como máximo."),
        // --- pomodoro ---
        ["Only active or paused runs can be cancelled."] = new("Seule une session en cours ou en pause peut être arrêtée.", "Se sèlman yon sesyon k ap fèt oswa ki an poz ou ka kanpe.", "Solo se puede detener una sesión en curso o en pausa."),
        ["Only active or paused runs can be finished."] = new("Seule une session en cours ou en pause peut être terminée.", "Se sèlman yon sesyon k ap fèt oswa ki an poz ou ka fini.", "Solo se puede terminar una sesión en curso o en pausa."),
        ["Only active runs can advance phases."] = new("Seule une session en cours peut changer de phase.", "Se sèlman yon sesyon k ap fèt ki ka chanje faz.", "Solo una sesión en curso puede cambiar de fase."),
        ["Only active runs can be paused."] = new("Seule une session en cours peut être mise en pause.", "Se sèlman yon sesyon k ap fèt ou ka mete an poz.", "Solo se puede pausar una sesión en curso."),
        ["Only paused runs can be resumed."] = new("Seule une session en pause peut être reprise.", "Se sèlman yon sesyon ki an poz ou ka rekòmanse.", "Solo se puede reanudar una sesión en pausa."),
        ["Run phase index mismatch. Refresh run state and retry."] = new("La session a changé de phase entre-temps. Actualisez et réessayez.", "Sesyon an chanje faz pandan tan sa a. Aktyalize epi eseye ankò.", "La sesión cambió de fase mientras tanto. Actualiza e inténtalo de nuevo."),
        ["The run changed at the same moment. Refresh run state and retry."] = new("La session a changé au même moment. Actualisez et réessayez.", "Sesyon an chanje nan menm moman an. Aktyalize epi eseye ankò.", "La sesión cambió en el mismo momento. Actualiza e inténtalo de nuevo."),
        ["This todo already has an active Pomodoro run."] = new("Cette tâche a déjà une session en cours.", "Travay sa a gen yon sesyon k ap fèt deja.", "Esta tarea ya tiene una sesión en curso."),
        ["No active Pomodoro run found for this todo."] = new("Cette tâche n'a aucune session en cours.", "Travay sa a pa gen okenn sesyon k ap fèt.", "Esta tarea no tiene ninguna sesión en curso."),
        ["At least one Pomodoro phase is required."] = new("Il faut au moins une phase.", "Fòk gen omwen yon faz.", "Se necesita al menos una fase."),
        ["Cannot attach an empty Pomodoro template."] = new("Un cycle vide ne peut pas être attaché.", "Ou pa ka tache yon sik ki vid.", "No se puede asignar un ciclo vacío."),
        ["Cannot start a Pomodoro run from an empty template."] = new("Impossible de démarrer une session avec un cycle vide.", "Ou pa ka kòmanse yon sesyon ak yon sik ki vid.", "No se puede empezar una sesión con un ciclo vacío."),
        ["Phase duration must be greater than zero."] = new("La durée d'une phase doit être supérieure à zéro.", "Dire yon faz dwe pi gran pase zewo.", "La duración de una fase debe ser mayor que cero."),
        ["Only active or paused runs can change their end."] = new("Seule une session active ou en pause peut changer son heure de fin.", "Se sèlman yon seyans k ap mache oswa ki an poz ki ka chanje lè li fini.", "Solo una sesión en curso o en pausa puede cambiar su hora de fin."),
        ["Each phase lasts 1 to 240 minutes."] = new("Chaque phase dure de 1 à 240 minutes.", "Chak etap dire ant 1 ak 240 minit.", "Cada fase dura de 1 a 240 minutos."),
        ["A cycle has at most 24 phases."] = new("Un cycle compte au plus 24 phases.", "Yon sik gen 24 etap pou pi plis.", "Un ciclo tiene 24 fases como máximo."),
        ["A note is at most 4,000 characters."] = new("Une note compte au plus 4 000 caractères.", "Yon nòt pa ka depase 4 000 karaktè.", "Una nota tiene 4 000 caracteres como máximo."),
        ["A session ends between 1 minute and 24 hours from now."] = new("Une session se termine entre 1 minute et 24 heures à partir de maintenant.", "Yon seyans fini ant 1 minit ak 24 èdtan apati kounye a.", "Una sesión termina entre 1 minuto y 24 horas a partir de ahora."),
        ["Template name is required."] = new("Le nom du cycle est obligatoire.", "Non sik la obligatwa.", "El nombre del ciclo es obligatorio."),
        ["This todo has no Pomodoro template attached."] = new("Cette tâche n'a pas de cycle Pomodoro.", "Travay sa a pa gen sik Pomodoro.", "Esta tarea no tiene un ciclo Pomodoro."),
        // --- identity ---
        ["Too many attempts. Try again in a moment."] = new("Trop de tentatives. Réessayez dans un moment.", "Twòp tantativ. Tann yon ti moman epi eseye ankò.", "Demasiados intentos. Inténtalo de nuevo en un momento."),
        ["The server is busy. Try again in a moment."] = new("Le serveur est surchargé. Réessayez dans un moment.", "Sèvè a twò chaje kounye a. Tann yon ti moman epi eseye ankò.", "El servidor está ocupado. Inténtalo de nuevo en un momento."),
        ["Invalid username or password"] = new("Nom d'utilisateur ou mot de passe invalide.", "Non itilizatè oswa modpas la pa bon.", "Nombre de usuario o contraseña incorrectos."),
        ["Invalid refresh token"] = new("Votre session a expiré. Reconnectez-vous.", "Sesyon ou an ekspire. Konekte ankò.", "Tu sesión expiró. Inicia sesión de nuevo."),
        ["The current password is not correct."] = new("Le mot de passe actuel n'est pas correct.", "Modpas aktyèl la pa bon.", "La contraseña actual no es correcta."),
        ["This account has no email address."] = new("Ce compte n'a pas d'adresse e-mail.", "Kont sa a pa gen adrès imèl.", "Esta cuenta no tiene correo electrónico."),
        ["This offer to keep the account has expired. Sign in again."] = new("Cette offre de garder le compte a expiré. Reconnectez-vous.", "Òf pou kenbe kont lan ekspire. Konekte ankò.", "Esta oferta para conservar la cuenta expiró. Inicia sesión de nuevo."),
        ["Subscriptions are not available yet."] = new("Les abonnements ne sont pas encore disponibles.", "Abònman yo poko disponib.", "Las suscripciones aún no están disponibles."),
        ["The calendar link is part of the phone subscription."] = new(
            "Le lien de calendrier fait partie de l'abonnement du téléphone.",
            "Lyen kalandriye a fè pati abònman telefòn nan.",
            "El enlace de calendario forma parte de la suscripción del teléfono."
        ),
        ["This purchase could not be verified."] = new("Cet achat n'a pas pu être vérifié.", "Nou pa t ka verifye acha sa a.", "No se pudo verificar esta compra."),
        ["This subscription belongs to another Kadans account."] = new("Cet abonnement appartient à un autre compte Kadans.", "Abònman sa a se pou yon lòt kont Kadans.", "Esta suscripción pertenece a otra cuenta de Kadans."),
        ["No account has that username or confirmed email address."] = new("Aucun compte n'a ce nom d'utilisateur ou cette adresse e-mail confirmée.", "Pa gen okenn kont ki gen non itilizatè sa a oswa adrès imel konfime sa a.", "Ninguna cuenta tiene ese nombre de usuario o ese correo electrónico confirmado."),
        ["Enter your current password."] = new("Saisissez votre mot de passe actuel.", "Antre modpas aktyèl ou.", "Escribe tu contraseña actual."),
        ["Too many failed attempts. Try again in a few minutes."] = new("Trop d'essais infructueux. Réessayez dans quelques minutes.", "Twòp tantativ ki pa mache. Eseye ankò nan kèk minit.", "Demasiados intentos fallidos. Inténtalo de nuevo en unos minutos."),
        ["A username can contain @ only when it is the account's email address."] = new("Un nom d'utilisateur ne peut contenir @ que s'il est l'adresse e-mail du compte.", "Yon non itilizatè ka gen @ sèlman si se adrès imèl kont lan.", "Un nombre de usuario solo puede contener @ si es el correo electrónico de la cuenta."),
        ["Page must be 1 or more."] = new("La page doit être 1 ou plus.", "Paj la dwe 1 oswa plis.", "La página debe ser 1 o más."),
        ["Page size must be between 1 and 100."] = new("La taille de page doit être comprise entre 1 et 100.", "Gwosè paj la dwe ant 1 ak 100.", "El tamaño de página debe estar entre 1 y 100."),
        ["User is deactivated"] = new("Ce compte est désactivé.", "Kont sa a dezaktive.", "Esta cuenta está desactivada."),
        ["Current user no longer exists."] = new("Ce compte n'existe plus.", "Kont sa a pa egziste ankò.", "Esta cuenta ya no existe."),
        ["Unable to resolve current user."] = new("Vous devez être connecté.", "Ou dwe konekte.", "Debes iniciar sesión."),
        ["User is not authenticated."] = new("Vous devez être connecté.", "Ou dwe konekte.", "Debes iniciar sesión."),
        ["User must be authenticated to create a todo."] = new("Vous devez être connecté pour créer une tâche.", "Ou dwe konekte pou kreye yon travay.", "Debes iniciar sesión para crear una tarea."),
        ["User must be authenticated."] = new("Vous devez être connecté.", "Ou dwe konekte.", "Debes iniciar sesión."),
        ["This email address is already in use."] = new("Cette adresse e-mail est déjà utilisée.", "Adrès imèl sa a deja itilize.", "Este correo electrónico ya está en uso."),
        ["The confirmation link is invalid or expired."] = new("Le lien de confirmation est invalide ou expiré.", "Lyen konfimasyon an pa valab oswa li ekspire.", "El enlace de confirmación no es válido o ya expiró."),
        ["The reset link is invalid or expired."] = new("Le lien de réinitialisation est invalide ou expiré.", "Lyen reyinisyalizasyon an pa valab oswa li ekspire.", "El enlace de restablecimiento no es válido o ya expiró."),
        ["The MFA token is invalid or expired."] = new("La vérification a expiré. Reconnectez-vous.", "Verifikasyon an ekspire. Konekte ankò.", "La verificación expiró. Inicia sesión de nuevo."),
        ["The verification code is not valid."] = new("Le code de vérification n'est pas valide.", "Kòd verifikasyon an pa valab.", "El código de verificación no es válido."),
        ["Two-factor authentication is already enabled."] = new("L'authentification à deux facteurs est déjà activée.", "Otantifikasyon ak de faktè deja aktive.", "La autenticación de dos factores ya está activada."),
        ["Disable two-factor authentication before enrolling again."] = new("Désactivez l'authentification à deux facteurs avant de la configurer à nouveau.", "Dezaktive otantifikasyon ak de faktè anvan ou konfigire l ankò.", "Desactiva la autenticación de dos factores antes de configurarla de nuevo."),
        ["Two-factor authentication is not enabled."] = new("L'authentification à deux facteurs n'est pas activée.", "Otantifikasyon ak de faktè pa aktive.", "La autenticación de dos factores no está activada."),
        ["Name is required."] = new("Le nom est obligatoire.", "Non an obligatwa.", "El nombre es obligatorio."),
        ["One or more roles are invalid."] = new("Un ou plusieurs rôles ne sont pas valides.", "Gen youn oswa plizyè wòl ki pa valab.", "Uno o más roles no son válidos."),
        ["Only admins can assign roles."] = new("Seuls les administrateurs peuvent attribuer des rôles.", "Se sèlman administratè yo ki ka bay wòl.", "Solo los administradores pueden asignar roles."),
        ["Only admins can create users via this endpoint."] = new("Seuls les administrateurs peuvent créer des utilisateurs ici.", "Se sèlman administratè yo ki ka kreye itilizatè la a.", "Solo los administradores pueden crear usuarios aquí."),
        ["Only admins can deactivate other users."] = new("Seuls les administrateurs peuvent désactiver d'autres utilisateurs.", "Se sèlman administratè yo ki ka dezaktive lòt itilizatè.", "Solo los administradores pueden desactivar a otros usuarios."),
        ["Only admins can update other users."] = new("Seuls les administrateurs peuvent modifier d'autres utilisateurs.", "Se sèlman administratè yo ki ka modifye lòt itilizatè.", "Solo los administradores pueden modificar a otros usuarios."),
        ["Only admins can update roles."] = new("Seuls les administrateurs peuvent modifier les rôles.", "Se sèlman administratè yo ki ka modifye wòl yo.", "Solo los administradores pueden modificar los roles."),
        // --- external sign-in ---
        ["An authorization code, its PKCE verifier and a loopback redirect URI are required."] = new("La connexion avec Google n'a pas abouti. Réessayez.", "Koneksyon ak Google la pa fin fèt. Eseye ankò.", "No se pudo iniciar sesión con Google. Inténtalo de nuevo."),
        ["Could not create an account from this login."] = new("Impossible de créer un compte à partir de cette connexion.", "Nou pa rive kreye yon kont ak koneksyon sa a.", "No se pudo crear una cuenta con este inicio de sesión."),
        ["Could not link this login to the account."] = new("Impossible d'associer cette connexion au compte.", "Nou pa rive mare koneksyon sa a ak kont lan.", "No se pudo vincular este inicio de sesión con la cuenta."),
        ["This sign-in did not come with a verified email address."] = new("Cette connexion n'a pas fourni d'adresse e-mail vérifiée.", "Koneksyon sa a pa bay okenn adrès imèl ki verifye.", "Este inicio de sesión no incluyó un correo electrónico verificado."),
        ["Could not reach Google to complete the sign-in."] = new("Impossible de joindre Google pour terminer la connexion.", "Nou pa rive jwenn Google pou fini koneksyon an.", "No se pudo contactar a Google para completar el inicio de sesión."),
        ["Google did not accept the sign-in code."] = new("Google n'a pas accepté la connexion. Réessayez.", "Google pa aksepte koneksyon an. Eseye ankò.", "Google no aceptó el inicio de sesión. Inténtalo de nuevo."),
        ["Google returned no ID token."] = new("La connexion avec Google n'a pas abouti. Réessayez.", "Koneksyon ak Google la pa fin fèt. Eseye ankò.", "No se pudo iniciar sesión con Google. Inténtalo de nuevo."),
        ["The ID token has no subject."] = new("La connexion n'a pas abouti. Réessayez.", "Koneksyon an pa fin fèt. Eseye ankò.", "No se pudo iniciar sesión. Inténtalo de nuevo."),
        ["The ID token is not valid."] = new("La connexion n'a pas abouti. Réessayez.", "Koneksyon an pa fin fèt. Eseye ankò.", "No se pudo iniciar sesión. Inténtalo de nuevo."),
        ["Google desktop sign-in is not configured (ExternalAuth:Google:Desktop:ClientId / ClientSecret)."] = new("La connexion avec Google n'est pas configurée sur ce serveur.", "Koneksyon ak Google pa konfigire sou sèvè sa a.", "El inicio de sesión con Google no está configurado en este servidor."),
        // --- budget ---
        ["Account name is required."] = new("Le nom du compte est obligatoire.", "Non kont lan obligatwa.", "El nombre de la cuenta es obligatorio."),
        ["Category name is required."] = new("Le nom de la catégorie est obligatoire.", "Non kategori a obligatwa.", "El nombre de la categoría es obligatorio."),
        ["Provide a real year and month."] = new("Indiquez une année et un mois valides.", "Mete yon ane ak yon mwa ki valab.", "Indica un año y un mes válidos."),
        ["A limit keeps its currency: remove it to set one in another currency."] = new("Une limite garde sa devise : supprimez-la pour en fixer une dans une autre devise.", "Yon limit kenbe lajan li: efase l pou mete youn nan yon lòt lajan.", "Un límite conserva su moneda: quítalo para fijar uno en otra moneda."),
        ["A movement's note is at most 1,000 characters."] = new("La note d'un mouvement compte au plus 1 000 caractères.", "Nòt yon mouvman pa ka depase 1 000 karaktè.", "La nota de un movimiento tiene 1 000 caracteres como máximo."),
        ["A recurring transaction can repeat at most once a day."] = new("Un mouvement récurrent peut se répéter au plus une fois par jour.", "Yon mouvman ki repete ka fèt yon fwa pa jou pou pi plis.", "Un movimiento recurrente puede repetirse una vez al día como máximo."),
        ["A recurring transaction can start at most a year ago."] = new("Un mouvement récurrent peut commencer il y a un an au plus.", "Yon mouvman ki repete ka kòmanse yon ane pase pou pi plis.", "Un movimiento recurrente puede empezar hace un año como máximo."),
        ["The destination account is archived."] = new("Le compte de destination est archivé.", "Kont destinasyon an achive.", "La cuenta de destino está archivada."),
        ["This account is archived."] = new("Ce compte est archivé.", "Kont sa a achive.", "Esta cuenta está archivada."),
        ["Budgets apply to expense categories."] = new("Les budgets s'appliquent aux catégories de dépenses.", "Bidjè yo se pou kategori depans sèlman.", "Los presupuestos se aplican a las categorías de gastos."),
        ["Transfers have no category."] = new("Un transfert n'a pas de catégorie.", "Yon transfè pa gen kategori.", "Una transferencia no tiene categoría."),
        ["Cross-currency transfers need the received amount."] = new("Un transfert entre deux devises nécessite le montant reçu.", "Yon transfè ant de lajan diferan bezwen montan ou resevwa a.", "Una transferencia entre dos monedas necesita el monto recibido."),
        ["Same-currency transfers move one single amount."] = new("Un transfert dans la même devise ne porte qu'un seul montant.", "Yon transfè nan menm lajan an gen yon sèl montan.", "Una transferencia en la misma moneda lleva un solo monto."),
        ["The base currency needs no rate — it is worth 1 of itself."] = new("La devise de base n'a pas besoin de taux : elle vaut 1.", "Lajan de baz la pa bezwen to: li vo 1.", "La moneda base no necesita tasa: vale 1."),
        ["Amount can have at most two decimals."] = new("Le montant peut avoir au plus deux décimales.", "Montan an ka gen de chif apre vigil la pou pi plis.", "El monto puede tener dos decimales como máximo."),
        ["Amount is out of range."] = new("Le montant est hors limites.", "Montan an depase limit yo.", "El monto está fuera de los límites."),
        ["Amount must be greater than zero."] = new("Le montant doit être supérieur à zéro.", "Montan an dwe pi gran pase zewo.", "El monto debe ser mayor que cero."),
        ["Initial balance can have at most two decimals."] = new("Le solde initial peut avoir au plus deux décimales.", "Balans kòmansman an ka gen de chif apre vigil la pou pi plis.", "El saldo inicial puede tener dos decimales como máximo."),
        ["The rate can have at most six decimals."] = new("Le taux peut avoir au plus six décimales.", "To a ka gen sis chif apre vigil la pou pi plis.", "La tasa puede tener seis decimales como máximo."),
        ["The rate must be positive."] = new("Le taux doit être positif.", "To a dwe pozitif.", "La tasa debe ser positiva."),
        ["A transfer needs a destination account."] = new("Un transfert nécessite un compte de destination.", "Yon transfè bezwen yon kont destinasyon.", "Una transferencia necesita una cuenta de destino."),
        ["A transfer needs two different accounts."] = new("Un transfert nécessite deux comptes différents.", "Yon transfè bezwen de kont diferan.", "Una transferencia necesita dos cuentas diferentes."),
        ["Only transfers involve a second account."] = new("Seul un transfert utilise un second compte.", "Se sèlman yon transfè ki sèvi ak yon dezyèm kont.", "Solo una transferencia usa una segunda cuenta."),
    };
}
