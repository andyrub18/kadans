package app.kadans.profile

import kotlin.math.abs
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.offsetAt

/** One choice in the time-zone picker: "Port-au-Prince", "America", "UTC−4" (the offset when the list was built). */
data class TimeZoneEntry(val id: String, val city: String, val region: String, val offset: String)

/**
 * The time zones a person can pick: IANA region/city ids, which the server's tz database knows too, plus UTC.
 * Fixed offsets ("GMT-04:00"), `Etc/` zones and legacy POSIX names are left out: they ignore daylight saving
 * and nobody lives in them.
 */
object TimeZoneCatalog {
    private val areas = setOf("Africa", "America", "Antarctica", "Asia", "Atlantic", "Australia", "Europe", "Indian", "Pacific")

    fun isSelectable(id: String): Boolean = id == "UTC" || ('/' in id && id.substringBefore('/') in areas)

    /** Every selectable zone, by city name. */
    fun entries(now: Instant, ids: Collection<String> = TimeZone.availableZoneIds): List<TimeZoneEntry> =
        ids.filter(::isSelectable)
            .mapNotNull { id -> runCatching { entry(id, now) }.getOrNull() }
            .sortedWith(compareBy({ it.city.lowercase() }, { it.region }))

    fun entry(id: String, now: Instant): TimeZoneEntry {
        val parts = id.split('/')
        return TimeZoneEntry(
            id = id,
            city = parts.last().replace('_', ' '),
            region = parts.dropLast(1).joinToString(" · ") { it.replace('_', ' ') },
            offset = offsetLabel(TimeZone.of(id).offsetAt(now)),
        )
    }

    /** "UTC", "UTC+1", "UTC−4", "UTC+5:30", with a real minus sign. */
    fun offsetLabel(offset: UtcOffset): String {
        val minutes = offset.totalSeconds / 60
        if (minutes == 0) return "UTC"
        val sign = if (minutes < 0) "−" else "+"
        val rest = abs(minutes) % 60
        return "UTC$sign${abs(minutes) / 60}" + if (rest == 0) "" else ":" + rest.toString().padStart(2, '0')
    }

    /**
     * City and region words whatever the case and punctuation ("port au", "new-york"), or a signed offset
     * ("utc-4", "+5:30"). "UTC−1" is not "UTC−10"; "+5" covers "+5:30" and "+5:45".
     */
    fun matches(entry: TimeZoneEntry, query: String): Boolean {
        val wanted = words(query)
        if (wanted.isEmpty()) return true
        if (wanted in words("${entry.city} ${entry.region} ${entry.id}")) return true
        val offset = offsetKey(query)
        if (offset.length < 2 || offset[0] !in "+-") return false
        val own = offsetKey(entry.offset)
        return own.startsWith(offset) && (':' in offset || own.getOrNull(offset.length)?.isDigit() != true)
    }

    private fun words(text: String): String =
        text.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter { it.isNotEmpty() }.joinToString(" ")

    private fun offsetKey(text: String): String =
        text.lowercase().replace('−', '-').filter { it.isDigit() || it == '+' || it == '-' || it == ':' }
}

/**
 * This device's time zone when it is one worth following: a real region/city zone. A device that only says
 * "UTC" (a desktop whose zone was never set) or a raw offset is not trusted; the person picks instead.
 */
fun deviceTimeZone(): String? = usableDeviceZone(TimeZone.currentSystemDefault().id)

internal fun usableDeviceZone(id: String): String? = id.takeIf { it != "UTC" && TimeZoneCatalog.isSelectable(it) }
