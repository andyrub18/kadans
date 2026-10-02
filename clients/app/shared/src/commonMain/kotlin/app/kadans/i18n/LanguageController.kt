package app.kadans.i18n

import app.kadans.api.KadansApi
import app.kadans.api.model.UpdateSelfUserRequest
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What to do with the account's language when this device signs in ([LanguageController.syncWith]). */
sealed interface LanguageSync {
    /** A choice made on this device that the account has not taken yet. */
    data class Push(val tag: String) : LanguageSync

    /** The account's language, for an install that never chose one or after another device changed it. */
    data class Adopt(val tag: String) : LanguageSync

    data object None : LanguageSync
}

/**
 * The chosen language, persisted per device. First run follows the device language; after that, the in-app
 * choice wins (in Haiti the phone is often set to French or English while the user prefers Kreyòl). The account
 * keeps the same language, so server-rendered content (emails, push notifications) speaks it too: a choice made
 * here is sent to the account, and an install that never chose takes the account's language at sign-in.
 */
class LanguageController(private val settings: Settings, private val api: KadansApi) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _language = MutableStateFlow(
        Language.fromTag(settings.getStringOrNull(KEY) ?: systemLanguageTag())
    )
    val language: StateFlow<Language> = _language.asStateFlow()

    fun set(language: Language) {
        settings.putString(KEY, language.tag)
        _language.value = language
        // Before sign-in this fails quietly; the next sign-in sends it (syncWith → Push).
        scope.launch {
            runCatching { api.account.update(UpdateSelfUserRequest(language = language.tag)) }
                .onSuccess { markSynced(language.tag) }
        }
    }

    fun cycle() = set(Language.next(_language.value))

    /** Shows the account's language on this device, without sending it back. */
    fun adopt(tag: String) {
        val language = Language.fromTag(tag)
        settings.putString(KEY, language.tag)
        markSynced(language.tag)
        _language.value = language
    }

    fun markSynced(tag: String) = settings.putString(SYNCED_KEY, tag)

    fun syncWith(accountTag: String): LanguageSync =
        decide(chosen = settings.getStringOrNull(KEY), synced = settings.getStringOrNull(SYNCED_KEY), account = accountTag)

    companion object {
        private const val KEY = "kadans.language"
        private const val SYNCED_KEY = "kadans.language.synced"

        /** The tag to send as Accept-Language: the in-app choice, else the device language. */
        fun currentTag(settings: Settings): String =
            Language.fromTag(settings.getStringOrNull(KEY) ?: systemLanguageTag()).tag

        /**
         * [chosen]: picked on this install (null: never). [synced]: what the account last confirmed from here.
         * The latest explicit choice wins, and a new install follows the account instead of overwriting it with
         * the phone's system language.
         */
        internal fun decide(chosen: String?, synced: String?, account: String): LanguageSync = when {
            chosen == null -> LanguageSync.Adopt(account)
            chosen != synced -> LanguageSync.Push(chosen)
            account != chosen -> LanguageSync.Adopt(account)
            else -> LanguageSync.None
        }
    }
}
