"""The website's words, in English, French and Kreyòl (site/generate.py puts them in pages). The legal pages are in
legal/. A text may hold a little markup (a link, a command): it goes into the page as written. Screen and button
names are the app's own (clients/app/shared/src/commonMain/kotlin/app/kadans/i18n): keep them in step.
"""

LANGUAGES = ["en", "fr", "ht"]

PLAY_STORE = "https://play.google.com/store/apps/details?id=app.kadans"
RELEASES = "https://github.com/andyrub18/kadans/releases"
SUPPORT = "support@kadansplanning.com"
DELETION_PAGE = "https://api.kadansplanning.com/account/delete"

TEXTS = {
    "en": {
        "language_name": "English",
        "language_label": "Language",
        "nav_label": "Main",
        "nav_download": "Download",
        "nav_support": "Support",
        "footer_privacy": "Privacy",
        "footer_terms": "Terms",
        "footer_support": "Support",
        "footer_delete": "Delete my account",
        "description": "Kadans keeps your tasks, your focus sessions and your budget in one place, and reminds you at "
                       "the moment it matters. On Android, Linux and Windows, in English, French and Kreyòl.",
        "play_button": "Get it on Google Play",

        "home_title": "Kadans – your days, in rhythm",
        "home_h1": "Your days, in rhythm.",
        "home_lead": "Kadans keeps your tasks, your focus and your money in one place, and reminds you at the moment "
                     "it matters.",
        "home_desktop_button": "Download for your computer",
        "home_price": "Free on your computer. On Android, USD 0.99 a month after a 14-day free trial.",
        "home_features_title": "Three things, done well",
        "f1_title": "Tasks that come back",
        "f1_text": "Once, or on a rhythm: every morning, the 15th and the last day of the month, the last Friday. "
                   "Kadans reminds you a little before, and again when it is time to start.",
        "f2_title": "Focus, one cycle at a time",
        "f2_text": "Give a task a Pomodoro cycle. Kadans keeps the time, asks before starting another cycle, and "
                   "shows how long you really focused.",
        "f3_title": "A budget in your currencies",
        "f3_text": "Accounts in gourdes, US and Canadian dollars, euros, Dominican and Mexican pesos. Transfers with "
                   "their exchange, limits per category, and the salary that books itself every month.",
        "home_sync_title": "Your phone and your computer, in step",
        "home_sync_text": "Mark a task done on one, and it is done on the other. Reminders ring on your phone even "
                          "without internet, and the computer app waits quietly by the clock.",
        "home_languages": "In English, French and Kreyòl.",
        "home_private_title": "Yours, and only yours",
        "home_private_text": "No ads, no tracking, nothing sold. Kadans keeps your data on its own server, and shares "
                             "it only to deliver your notifications and emails.",
        "home_private_link": "How we handle your data",

        "dl_title": "Download Kadans",
        "dl_h1": "Download Kadans",
        "dl_lead": "Sign in with the same account everywhere: your tasks, focus sessions and budget follow you.",
        "dl_phone_title": "On your phone",
        "dl_android_note": "USD 0.99 a month after a 14-day free trial.",
        "dl_later": "Coming later.",
        "dl_desktop_title": "On your computer, free",
        "dl_suggested": "For this computer",
        "dl_deb_title": "Linux: Ubuntu, Debian, Mint, <span class=\"nowrap\">Pop!_OS</span>",
        "dl_deb_button": "Download the .deb",
        "dl_deb_install": "Then: <code>sudo apt install ./kadans-linux-amd64.deb</code>",
        "dl_rpm_title": "Linux: Fedora, openSUSE",
        "dl_rpm_button": "Download the .rpm",
        "dl_rpm_install": "Then: <code>sudo dnf install ./kadans-linux-x86_64.rpm</code>",
        "dl_win_title": "Windows 10 and 11",
        "dl_win_button": "Download the .msi",
        "dl_win_install": "Open it to install. If Windows says it protected your PC, choose <em>More info</em>, then "
                          "<em>Run anyway</em>.",
        "dl_updates": 'Kadans tells you when a new version is out. Every version, with its checksums: '
                      '<a href="{releases}">the releases page</a>.',

        "support_title": "Support – Kadans",
        "support_h1": "Support",
        "support_lead": f'Write to <a href="mailto:{SUPPORT}">{SUPPORT}</a>, in English, French or Kreyòl.',
        "support_faq": "Common questions",
        "q1": "My reminders don't ring on my Android phone.",
        "a1": "In Kadans, open Settings → Reminders on this phone, and allow notifications and “Alarms &amp; "
              "reminders”. Some phones also put apps to sleep: in your phone's battery settings, let Kadans run "
              "without restrictions.",
        "q2": "I forgot my password.",
        "a2": "On the sign-in screen, tap “Forgot password?” and open the link we email you.",
        "q3": "How do I cancel my subscription?",
        "a3": "In Google Play: your profile → Payments &amp; subscriptions → Subscriptions → Kadans. You keep Kadans "
              "until the end of the period you paid for.",
        "q4": "How do I delete my account?",
        "a4": 'See <a href="{delete}">Delete my account</a>.',
        "q5": "Is the computer app really free?",
        "a5": "Yes. A subscription is only needed on phones.",

        "del_title": "Delete my account – Kadans",
        "del_h1": "Delete my account",
        "del_lead": "You can delete your Kadans account at any time, with or without the app.",
        "del_app_title": "In the app",
        "del_app": "Settings → Delete my account, then confirm with your password. If you sign in with Google only, "
                   "Kadans emails you a link to confirm instead.",
        "del_web_title": "Without the app",
        "del_web": f'Open <a href="{DELETION_PAGE}">the account deletion page</a>, enter your email address, and '
                   "open the link we send you.",
        "del_what_title": "What happens",
        "del_what": [
            "Your account closes at once: you are signed out everywhere.",
            "Seven days later, it is erased with everything in it: tasks, focus history, budget. Sign in before "
            "then to keep it.",
            "The copies in our nightly backups are gone 14 days after that.",
            "A Google Play subscription stops renewing when the account is erased. To stop it sooner, cancel it in "
            "Google Play.",
        ],

        "privacy_title": "Privacy policy – Kadans",
        "terms_title": "Terms of use – Kadans",
        "nf_h1": "This page does not exist.",
        "nf_home": "Kadans home",
    },
    "fr": {
        "language_name": "Français",
        "language_label": "Langue",
        "nav_label": "Principal",
        "nav_download": "Télécharger",
        "nav_support": "Assistance",
        "footer_privacy": "Confidentialité",
        "footer_terms": "Conditions",
        "footer_support": "Assistance",
        "footer_delete": "Supprimer mon compte",
        "description": "Kadans réunit vos tâches, vos séances de concentration et votre budget, et vous rappelle au "
                       "bon moment. Sur Android, Linux et Windows, en français, en anglais et en kreyòl.",
        "play_button": "Disponible sur Google Play",

        "home_title": "Kadans – vos journées, en rythme",
        "home_h1": "Vos journées, en rythme.",
        "home_lead": "Kadans réunit vos tâches, votre concentration et votre argent au même endroit, et vous rappelle "
                     "au moment qui compte.",
        "home_desktop_button": "Télécharger pour votre ordinateur",
        "home_price": "Gratuit sur ordinateur. Sur Android, 0,99 USD par mois après 14 jours d'essai gratuit.",
        "home_features_title": "Trois choses, bien faites",
        "f1_title": "Des tâches qui reviennent",
        "f1_text": "Une fois, ou selon un rythme : chaque matin, le 15 et le dernier jour du mois, le dernier "
                   "vendredi. Kadans vous prévient un peu avant, puis au moment de commencer.",
        "f2_title": "La concentration, un cycle à la fois",
        "f2_text": "Donnez un cycle Pomodoro à une tâche. Kadans tient le temps, demande avant de lancer un autre "
                   "cycle, et montre combien de temps vous vous êtes vraiment concentré.",
        "f3_title": "Un budget dans vos devises",
        "f3_text": "Des comptes en gourdes, en dollars américains et canadiens, en euros, en pesos dominicains et "
                   "mexicains. Des virements avec leur change, des limites par catégorie, et le salaire qui "
                   "s'inscrit tout seul chaque mois.",
        "home_sync_title": "Votre téléphone et votre ordinateur, au même pas",
        "home_sync_text": "Marquez une tâche comme faite sur l'un, elle l'est sur l'autre. Les rappels sonnent sur "
                          "votre téléphone même sans internet, et l'application pour ordinateur attend discrètement "
                          "près de l'horloge.",
        "home_languages": "En français, en anglais et en kreyòl.",
        "home_private_title": "À vous, et à vous seul",
        "home_private_text": "Pas de publicité, pas de pistage, rien n'est vendu. Kadans garde vos données sur son "
                             "propre serveur, et ne les partage que pour livrer vos notifications et vos e-mails.",
        "home_private_link": "Comment nous traitons vos données",

        "dl_title": "Télécharger Kadans",
        "dl_h1": "Télécharger Kadans",
        "dl_lead": "Connectez-vous avec le même compte partout : vos tâches, vos séances et votre budget vous "
                   "suivent.",
        "dl_phone_title": "Sur votre téléphone",
        "dl_android_note": "0,99 USD par mois après 14 jours d'essai gratuit.",
        "dl_later": "Bientôt.",
        "dl_desktop_title": "Sur votre ordinateur, gratuit",
        "dl_suggested": "Pour cet ordinateur",
        "dl_deb_title": "Linux : Ubuntu, Debian, Mint, <span class=\"nowrap\">Pop!_OS</span>",
        "dl_deb_button": "Télécharger le .deb",
        "dl_deb_install": "Ensuite : <code>sudo apt install ./kadans-linux-amd64.deb</code>",
        "dl_rpm_title": "Linux : Fedora, openSUSE",
        "dl_rpm_button": "Télécharger le .rpm",
        "dl_rpm_install": "Ensuite : <code>sudo dnf install ./kadans-linux-x86_64.rpm</code>",
        "dl_win_title": "Windows 10 et 11",
        "dl_win_button": "Télécharger le .msi",
        "dl_win_install": "Ouvrez-le pour l'installer. Si Windows dit avoir protégé votre ordinateur, choisissez "
                          "<em>Informations complémentaires</em>, puis <em>Exécuter quand même</em>.",
        "dl_updates": 'Kadans vous prévient quand une nouvelle version sort. Toutes les versions, avec leurs sommes '
                      'de contrôle : <a href="{releases}">la page des versions</a>.',

        "support_title": "Assistance – Kadans",
        "support_h1": "Assistance",
        "support_lead": f'Écrivez à <a href="mailto:{SUPPORT}">{SUPPORT}</a>, en français, en anglais ou en kreyòl.',
        "support_faq": "Questions fréquentes",
        "q1": "Mes rappels ne sonnent pas sur mon téléphone Android.",
        "a1": "Dans Kadans, ouvrez Paramètres → Rappels sur ce téléphone, et autorisez les notifications et "
              "« Alarmes et rappels ». Certains téléphones endorment aussi les applications : dans les réglages de "
              "batterie de votre téléphone, laissez Kadans fonctionner sans restriction.",
        "q2": "J'ai oublié mon mot de passe.",
        "a2": "Sur l'écran de connexion, touchez « Mot de passe oublié ? » et ouvrez le lien que nous vous "
              "envoyons par e-mail.",
        "q3": "Comment annuler mon abonnement ?",
        "a3": "Dans Google Play : votre profil → Paiements et abonnements → Abonnements → Kadans. Vous gardez Kadans "
              "jusqu'à la fin de la période payée.",
        "q4": "Comment supprimer mon compte ?",
        "a4": 'Voir <a href="{delete}">Supprimer mon compte</a>.',
        "q5": "L'application pour ordinateur est-elle vraiment gratuite ?",
        "a5": "Oui. L'abonnement ne concerne que les téléphones.",

        "del_title": "Supprimer mon compte – Kadans",
        "del_h1": "Supprimer mon compte",
        "del_lead": "Vous pouvez supprimer votre compte Kadans à tout moment, avec ou sans l'application.",
        "del_app_title": "Dans l'application",
        "del_app": "Paramètres → Supprimer mon compte, puis confirmez avec votre mot de passe. Si vous vous connectez "
                   "seulement avec Google, Kadans vous envoie plutôt un lien par e-mail pour confirmer.",
        "del_web_title": "Sans l'application",
        "del_web": f'Ouvrez <a href="{DELETION_PAGE}">la page de suppression du compte</a>, saisissez votre adresse '
                   "e-mail et ouvrez le lien que nous vous envoyons.",
        "del_what_title": "Ce qui se passe",
        "del_what": [
            "Votre compte est fermé aussitôt : vous êtes déconnecté partout.",
            "Sept jours plus tard, il est effacé avec tout son contenu : tâches, historique de concentration, "
            "budget. Connectez-vous avant pour le garder.",
            "Les copies de nos sauvegardes nocturnes disparaissent 14 jours après.",
            "Un abonnement Google Play cesse de se renouveler quand le compte est effacé. Pour l'arrêter plus tôt, "
            "annulez-le sur Google Play.",
        ],

        "privacy_title": "Politique de confidentialité – Kadans",
        "terms_title": "Conditions d'utilisation – Kadans",
        "nf_h1": "Cette page n'existe pas.",
        "nf_home": "Accueil de Kadans",
    },
    "ht": {
        "language_name": "Kreyòl",
        "language_label": "Lang",
        "nav_label": "Prensipal",
        "nav_download": "Telechaje",
        "nav_support": "Èd",
        "footer_privacy": "Konfidansyalite",
        "footer_terms": "Kondisyon",
        "footer_support": "Èd",
        "footer_delete": "Efase kont mwen",
        "description": "Kadans mete travay ou, seyans konsantrasyon ou ak bidjè ou ansanm, epi li fè ou sonje nan bon "
                       "moman an. Sou Android, Linux ak Windows, an kreyòl, an franse ak an angle.",
        "play_button": "Jwenn li sou Google Play",

        "home_title": "Kadans – jounen ou, nan bon ritm",
        "home_h1": "Jounen ou, nan bon ritm.",
        "home_lead": "Kadans mete travay ou, konsantrasyon ou ak lajan ou yon sèl kote, epi li fè ou sonje nan "
                     "moman ki konte a.",
        "home_desktop_button": "Telechaje pou òdinatè ou",
        "home_price": "Gratis sou òdinatè. Sou Android, 0,99 USD pa mwa apre 14 jou esè gratis.",
        "home_features_title": "Twa bagay, byen fèt",
        "f1_title": "Travay ki retounen",
        "f1_text": "Yon sèl fwa, oswa sou yon ritm: chak maten, 15 ak dènye jou nan mwa a, dènye vandredi a. Kadans "
                   "fè ou sonje yon ti tan anvan, epi ankò lè li lè pou kòmanse.",
        "f2_title": "Konsantrasyon, yon sik apre lòt",
        "f2_text": "Bay yon travay yon sik Pomodoro. Kadans kenbe tan an, li mande ou anvan li lanse yon lòt sik, epi "
                   "li montre konbyen tan ou te vrèman konsantre.",
        "f3_title": "Yon bidjè nan lajan pa w",
        "f3_text": "Kont an goud, an dola ameriken ak kanadyen, an ewo, an peso dominiken ak meksiken. Transfè ak "
                   "chanj yo, limit pa kategori, ak salè a ki anrejistre pou kont li chak mwa.",
        "home_sync_title": "Telefòn ou ak òdinatè ou, menm pa",
        "home_sync_text": "Make yon travay fini sou youn, li fini sou lòt la tou. Rapèl yo sonnen sou telefòn ou menm "
                          "san entènèt, epi aplikasyon òdinatè a ap tann dousman bò lè a.",
        "home_languages": "An kreyòl, an franse ak an angle.",
        "home_private_title": "Pou ou, pou ou sèlman",
        "home_private_text": "Pa gen piblisite, pa gen swivi, nou pa vann anyen. Kadans kenbe done ou sou pwòp sèvè "
                             "li, epi li pataje yo sèlman pou voye notifikasyon ak imèl ou yo.",
        "home_private_link": "Kijan nou okipe done ou",

        "dl_title": "Telechaje Kadans",
        "dl_h1": "Telechaje Kadans",
        "dl_lead": "Konekte ak menm kont lan tout kote: travay ou, seyans ou ak bidjè ou swiv ou.",
        "dl_phone_title": "Sou telefòn ou",
        "dl_android_note": "0,99 USD pa mwa apre 14 jou esè gratis.",
        "dl_later": "Pita.",
        "dl_desktop_title": "Sou òdinatè ou, gratis",
        "dl_suggested": "Pou òdinatè sa a",
        "dl_deb_title": "Linux: Ubuntu, Debian, Mint, <span class=\"nowrap\">Pop!_OS</span>",
        "dl_deb_button": "Telechaje .deb la",
        "dl_deb_install": "Apre sa: <code>sudo apt install ./kadans-linux-amd64.deb</code>",
        "dl_rpm_title": "Linux: Fedora, openSUSE",
        "dl_rpm_button": "Telechaje .rpm nan",
        "dl_rpm_install": "Apre sa: <code>sudo dnf install ./kadans-linux-x86_64.rpm</code>",
        "dl_win_title": "Windows 10 ak 11",
        "dl_win_button": "Telechaje .msi a",
        "dl_win_install": "Louvri l pou enstale l. Si Windows di li pwoteje òdinatè ou, chwazi <em>Informations "
                          "complémentaires</em> (More info), epi <em>Exécuter quand même</em> (Run anyway).",
        "dl_updates": 'Kadans di ou lè yon nouvo vèsyon soti. Tout vèsyon yo, ak sòm kontwòl yo: '
                      '<a href="{releases}">paj vèsyon yo</a>.',

        "support_title": "Èd – Kadans",
        "support_h1": "Èd",
        "support_lead": f'Ekri nou nan <a href="mailto:{SUPPORT}">{SUPPORT}</a>, an kreyòl, an franse oswa an angle.',
        "support_faq": "Kesyon moun poze souvan",
        "q1": "Rapèl mwen yo pa sonnen sou telefòn Android mwen.",
        "a1": "Nan Kadans, louvri Paramèt → Rapèl sou telefòn sa a, epi pèmèt notifikasyon ak « Alarmes et "
              "rappels » (Alarms &amp; reminders). Gen telefòn ki mete aplikasyon yo dòmi tou: nan paramèt batri "
              "telefòn ou, kite Kadans mache san restriksyon.",
        "q2": "Mwen bliye modpas mwen.",
        "a2": "Sou ekran koneksyon an, peze « Ou bliye modpas ou? » epi louvri lyen nou voye ba ou pa imèl la.",
        "q3": "Kijan pou m anile abònman mwen?",
        "a3": "Nan Google Play: pwofil ou → Peman ak abònman → Abònman → Kadans. Ou kenbe Kadans jiska fen peryòd ou "
              "te peye a.",
        "q4": "Kijan pou m efase kont mwen?",
        "a4": 'Gade <a href="{delete}">Efase kont mwen</a>.',
        "q5": "Èske aplikasyon òdinatè a gratis toutbon?",
        "a5": "Wi. Se sèlman sou telefòn ou bezwen yon abònman.",

        "del_title": "Efase kont mwen – Kadans",
        "del_h1": "Efase kont mwen",
        "del_lead": "Ou ka efase kont Kadans ou nenpòt ki lè, avèk oswa san aplikasyon an.",
        "del_app_title": "Nan aplikasyon an",
        "del_app": "Paramèt → Efase kont mwen, epi konfime ak modpas ou. Si ou konekte ak Google sèlman, Kadans voye "
                   "yon lyen ba ou pa imèl pito pou konfime.",
        "del_web_title": "San aplikasyon an",
        "del_web": f'Louvri <a href="{DELETION_PAGE}">paj pou efase kont lan</a>, antre adrès imèl ou, epi louvri '
                   "lyen nou voye ba ou a.",
        "del_what_title": "Sa k ap pase",
        "del_what": [
            "Kont ou fèmen touswit: ou dekonekte tout kote.",
            "Sèt jou apre, li efase ak tout sa ki ladan l: travay, istwa konsantrasyon, bidjè. Konekte anvan sa "
            "pou kenbe l.",
            "Kopi ki nan sovgad nou fè chak lannwit yo disparèt 14 jou apre sa.",
            "Yon abònman Google Play sispann renouvle lè kont lan efase. Pou rete l pi bonè, anile l sou Google "
            "Play.",
        ],

        "privacy_title": "Politik konfidansyalite – Kadans",
        "terms_title": "Kondisyon itilizasyon – Kadans",
        "nf_h1": "Paj sa a pa egziste.",
        "nf_home": "Lakay Kadans",
    },
}
