package app.kadans.updates

import app.kadans.config.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the site says of the latest desktop release (`https://kadansplanning.com/desktop/latest.json`, which nginx sends
 * to the latest GitHub release's `latest.json`, written by the release workflow). [minimumVersion]: older apps no longer
 * work with the server and must update. [notes]: what is new, by language tag.
 */
@Serializable
data class LatestRelease(
    val version: String,
    val minimumVersion: String? = null,
    val notes: Map<String, String> = emptyMap(),
)

/** A newer version is out ([required]: this one is older than the oldest that still works). */
data class UpdateNotice(val version: String, val required: Boolean, val notes: String?)

/**
 * The desktop app's update check (ARCHITECTURE → "The desktop app: in the tray, once"): it never downloads or installs
 * anything; it says a version is out and opens [DOWNLOAD_PAGE]. Phones are updated by their stores and never check.
 * [available] is what Settings and the "update required" dialog show; the desktop's schedule fills it.
 */
class AppUpdates(val currentVersion: String = BuildConfig.APP_VERSION) {
    private val _available = MutableStateFlow<UpdateNotice?>(null)
    val available: StateFlow<UpdateNotice?> = _available.asStateFlow()

    /**
     * Reads what the site answered ([body], null when it could not be reached) and returns the notice, if any, also
     * kept in [available]. A body that is not what the site serves changes nothing: a broken file never nags anyone.
     */
    fun read(body: String?, language: String): UpdateNotice? {
        val latest = body?.takeIf { it.length <= MAX_BODY }?.let { runCatching { json.decodeFromString<LatestRelease>(it) }.getOrNull() }
            ?: return _available.value
        val newer = isNewer(latest.version, currentVersion)
        val notice = if (newer) {
            UpdateNotice(
                version = latest.version,
                required = latest.minimumVersion?.let { isNewer(it, currentVersion) } == true,
                notes = latest.notes[language] ?: latest.notes["en"],
            )
        } else null
        _available.value = notice
        return notice
    }

    companion object {
        const val LATEST_URL = "https://kadansplanning.com/desktop/latest.json"

        /** The site's download page, in the visitor's language. */
        const val DOWNLOAD_PAGE = "https://kadansplanning.com/download"

        /** latest.json is a few hundred bytes: anything much bigger is not it. */
        internal const val MAX_BODY = 64 * 1024

        private val json = Json { ignoreUnknownKeys = true }

        /** MAJOR.MINOR.PATCH, anything after a dash or a plus left out ("1.2.0-beta" is 1.2.0); null when not one. */
        internal fun parse(version: String): List<Int>? {
            val parts = version.trim().substringBefore('-').substringBefore('+').split('.')
            if (parts.size != 3) return null
            return parts.map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
        }

        /** The notice to announce, once per version: none when there is none, or this version was announced already. */
        fun toAnnounce(notice: UpdateNotice?, lastAnnounced: String?): UpdateNotice? =
            notice?.takeIf { it.version != lastAnnounced }

        /** Whether [candidate] is a later version than [current]; never, when either is not a version. */
        internal fun isNewer(candidate: String, current: String): Boolean {
            val a = parse(candidate) ?: return false
            val b = parse(current) ?: return false
            for (i in 0..2) if (a[i] != b[i]) return a[i] > b[i]
            return false
        }
    }
}
