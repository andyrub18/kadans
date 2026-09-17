package app.kadans.config

import com.russhwolf.settings.Settings

/**
 * Where the app talks to, in order: the address typed on the Login screen (a phone on Wi-Fi testing
 * against a laptop), the server this build was made for (`-Pkadans.apiBaseUrl`, release builds), the
 * per-platform dev default (localhost / the emulator's host alias). Read per request, so a change
 * applies immediately — no restart.
 */
object ServerAddress {
    private const val KEY = "kadans.api.baseUrl"

    fun current(settings: Settings): String =
        resolve(overrideOrNull(settings), BuildConfig.API_BASE_URL, defaultApiBaseUrl())

    /** What the Login screen shows as "the default" when nothing is typed. */
    fun builtInDefault(): String = resolve(null, BuildConfig.API_BASE_URL, defaultApiBaseUrl())

    internal fun resolve(override: String?, builtFor: String?, platformDefault: String): String =
        override?.takeIf { it.isNotBlank() } ?: builtFor?.takeIf { it.isNotBlank() } ?: platformDefault

    fun overrideOrNull(settings: Settings): String? =
        settings.getStringOrNull(KEY)?.takeIf { it.isNotBlank() }

    fun setOverride(settings: Settings, url: String?) {
        val cleaned = url?.trim()?.trimEnd('/')
        if (cleaned.isNullOrBlank()) settings.remove(KEY) else settings.putString(KEY, cleaned)
    }
}
