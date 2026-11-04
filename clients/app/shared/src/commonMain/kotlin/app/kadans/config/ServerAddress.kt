package app.kadans.config

import com.russhwolf.settings.Settings

/**
 * Where the app talks to. A build made for a server (`-Pkadans.apiBaseUrl`: every release, every store build) talks to
 * that server and nothing else: no address can be typed on the device, and one saved by an earlier build is ignored,
 * so nobody holding the phone can send its sign-ins elsewhere. A dev build (no `-Pkadans.apiBaseUrl`) talks to the
 * address typed on the Login screen (a phone on Wi-Fi testing against a laptop), else the per-platform default
 * (localhost / the emulator's host alias). Read per request, so a change applies immediately — no restart.
 */
object ServerAddress {
    private const val KEY = "kadans.api.baseUrl"

    /** Whether the Login screen offers to change the address: dev builds only. */
    val adjustable: Boolean get() = BuildConfig.API_BASE_URL.isNullOrBlank()

    fun current(settings: Settings): String =
        resolve(overrideOrNull(settings), BuildConfig.API_BASE_URL, defaultApiBaseUrl())

    /** What the Login screen shows as "the default" when nothing is typed. */
    fun builtInDefault(): String = resolve(null, BuildConfig.API_BASE_URL, defaultApiBaseUrl())

    internal fun resolve(override: String?, builtFor: String?, platformDefault: String): String =
        builtFor?.takeIf { it.isNotBlank() } ?: override?.takeIf { it.isNotBlank() } ?: platformDefault

    fun overrideOrNull(settings: Settings): String? =
        settings.getStringOrNull(KEY)?.takeIf { it.isNotBlank() }

    fun setOverride(settings: Settings, url: String?) {
        val cleaned = url?.trim()?.trimEnd('/')
        if (cleaned.isNullOrBlank()) settings.remove(KEY) else settings.putString(KEY, cleaned)
    }
}
