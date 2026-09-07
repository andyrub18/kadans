package app.kadans.config

import com.russhwolf.settings.Settings

/**
 * Where the app talks to. The per-platform default suits emulators and the dev desktop; a real
 * phone on Wi-Fi needs the computer's LAN address, set from the Login screen. Read per request,
 * so a change applies immediately — no restart.
 */
object ServerAddress {
    private const val KEY = "kadans.api.baseUrl"

    fun current(settings: Settings): String =
        overrideOrNull(settings) ?: defaultApiBaseUrl()

    fun overrideOrNull(settings: Settings): String? =
        settings.getStringOrNull(KEY)?.takeIf { it.isNotBlank() }

    fun setOverride(settings: Settings, url: String?) {
        val cleaned = url?.trim()?.trimEnd('/')
        if (cleaned.isNullOrBlank()) settings.remove(KEY) else settings.putString(KEY, cleaned)
    }
}
