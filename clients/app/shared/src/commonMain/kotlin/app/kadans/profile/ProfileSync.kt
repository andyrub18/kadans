package app.kadans.profile

import app.kadans.api.KadansApi
import app.kadans.api.model.UpdateSelfUserRequest
import app.kadans.i18n.LanguageController
import app.kadans.i18n.LanguageSync

/**
 * Brings the account in line with this device after each sign-in and app start (Home runs it). The server
 * renders reminders, stats days, budget months and emails in the account's time zone and language:
 * - time zone: the device's, while this install follows it ([TimeZonePreference]); travel included;
 * - language: see [LanguageController.syncWith].
 * Best-effort: a failure changes nothing, and the next start tries again.
 */
class ProfileSync(
    private val api: KadansApi,
    private val timeZones: TimeZonePreference,
    private val languages: LanguageController,
    private val deviceZone: () -> String? = ::deviceTimeZone,
) {
    suspend fun sync() {
        val account = api.account.me()
        val language = languages.syncWith(account.language)
        if (language is LanguageSync.Adopt) languages.adopt(language.tag)

        val changes = changes(
            accountZone = account.timeZone,
            deviceZone = if (timeZones.followsDevice) deviceZone() else null,
            pushLanguage = (language as? LanguageSync.Push)?.tag,
        ) ?: return
        api.account.update(changes)
        changes.language?.let(languages::markSynced)
    }

    internal companion object {
        /** What to send, or null when the account already matches. */
        fun changes(accountZone: String, deviceZone: String?, pushLanguage: String?): UpdateSelfUserRequest? {
            val zone = deviceZone?.takeIf { it != accountZone }
            return if (zone == null && pushLanguage == null) null else UpdateSelfUserRequest(timeZone = zone, language = pushLanguage)
        }
    }
}
