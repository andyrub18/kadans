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

/**
 * The chosen language, persisted per device. First run follows the device language; after
 * that, the in-app choice wins (in Haiti the phone is often set to French or English while
 * the user prefers Kreyòl). A change is also synced to the profile so server-rendered
 * content — emails, push notifications — speaks the same language.
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
        scope.launch {
            runCatching { api.account.update(UpdateSelfUserRequest(language = language.tag)) }
        }
    }

    fun cycle() = set(Language.next(_language.value))

    private companion object {
        const val KEY = "kadans.language"
    }
}
