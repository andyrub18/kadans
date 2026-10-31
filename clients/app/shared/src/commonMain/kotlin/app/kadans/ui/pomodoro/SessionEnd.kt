package app.kadans.ui.pomodoro

import app.kadans.i18n.PomodoroStrings
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * When a session ends by itself. People think in clock times ("until 17:00"), the server in instants: a picked time
 * is its next occurrence, today if still ahead, else tomorrow. The server's limits apply: at least a minute away,
 * at most a day. Without a pick the server ends a session 12 hours after its start.
 */
object SessionEnd {
    val DEFAULT = 12.hours
    private val MIN_AHEAD = 1.minutes
    private val MAX_AHEAD = 24.hours

    fun next(time: LocalTime, now: Instant, zone: TimeZone): Instant {
        val today = now.toLocalDateTime(zone).date
        val sameDay = LocalDateTime(today, time).toInstant(zone)
        val at = if (sameDay >= now + MIN_AHEAD) sameDay else LocalDateTime(today.plus(1, DateTimeUnit.DAY), time).toInstant(zone)
        return minOf(at, now + MAX_AHEAD) // a day that gains an hour (autumn DST) could put it 25 hours away
    }

    /**
     * "Ends at 17:00", or "Ends tomorrow at 02:00" when it is past midnight; [withNext]: that is when the todo's next
     * occurrence starts ("…, when the next one starts").
     */
    fun label(end: Instant, now: Instant, zone: TimeZone, strings: PomodoroStrings, withNext: Boolean = false): String {
        val local = end.toLocalDateTime(zone)
        val clock = "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
        val today = local.date == now.toLocalDateTime(zone).date
        return when {
            withNext && today -> strings.endsAtNext(clock)
            withNext -> strings.endsTomorrowAtNext(clock)
            today -> strings.endsAt(clock)
            else -> strings.endsTomorrowAt(clock)
        }
    }
}
