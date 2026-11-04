package app.kadans.ui

import androidx.compose.runtime.staticCompositionLocalOf
import app.kadans.api.model.ClockFormat
import app.kadans.i18n.DateStrings
import app.kadans.i18n.EnglishStrings
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * Dates and times as every screen writes them, in the app's language ([DateStrings]) and the account's clock
 * ([app.kadans.profile.ClockPreference]): "Today", "Tomorrow", "Thursday, November 5" (the year only when it is not
 * this one), "9:00 PM" or "21:00". Never ISO. Screens take it from [LocalDates]; [today] is passed in so a test can
 * name it.
 */
class Dates(private val s: DateStrings, val clock: ClockFormat) {
    val twelveHour: Boolean get() = clock == ClockFormat.TwelveHour

    /** "21:00", "09:05", or "9:00 PM", "12:30 AM". */
    fun time(time: LocalTime): String = time(time, clock)

    fun time(at: Instant, zone: TimeZone): String = time(at.toLocalDateTime(zone).time)

    /** A day's heading: "Today", "Tomorrow", "Yesterday", else "Thursday, November 5". */
    fun day(date: LocalDate, today: LocalDate): String = relative(date, today) ?: long(date, today)

    /** A day in a list: "Today", "Tomorrow", "Yesterday", else "Thu, Nov 5". */
    fun shortDay(date: LocalDate, today: LocalDate): String = relative(date, today) ?: short(date, today)

    /** "Thursday, November 5", the day named even when it is today: above a calendar's list. */
    fun long(date: LocalDate, today: LocalDate): String =
        fill(if (date.year == today.year) s.dayPattern else s.dayYearPattern, date, s.weekdays, s.months)

    /** "Thu, Nov 5", the day named even when it is today: a date chosen in a form. */
    fun short(date: LocalDate, today: LocalDate): String =
        fill(if (date.year == today.year) s.shortPattern else s.shortYearPattern, date, s.weekdaysShort, s.monthsShort)

    /** "March 31, 2027", "31 mars 2027": a date inside a sentence ("until …"), with its year. */
    fun date(date: LocalDate): String =
        s.datePattern.replace("{d}", dayOfMonth(date)).replace("{m}", s.months[date.month.ordinal]).replace("{y}", "${date.year}")

    /** "Today · 9:00 PM", "Thu, Nov 5 · 21:00": when something happens, on the wall clock of [zone]. */
    fun moment(at: Instant, zone: TimeZone, today: LocalDate = today(zone)): String = moment(at.toLocalDateTime(zone), today)

    fun moment(local: LocalDateTime, today: LocalDate): String = shortDay(local.date, today) + " · " + time(local.time)

    private fun relative(date: LocalDate, today: LocalDate): String? = when (date) {
        today -> s.today
        today.plus(1, DateTimeUnit.DAY) -> s.tomorrow
        today.minus(1, DateTimeUnit.DAY) -> s.yesterday
        else -> null
    }

    private fun fill(pattern: String, date: LocalDate, weekdays: List<String>, months: List<String>): String =
        pattern
            .replace("{wd}", weekdays[date.dayOfWeek.isoDayNumber - 1])
            .replace("{d}", dayOfMonth(date))
            .replace("{m}", months[date.month.ordinal])
            .replace("{y}", "${date.year}")
            .replaceFirstChar { it.titlecase() } // "jeudi 5 novembre" starts a line: "Jeudi 5 novembre"

    private fun dayOfMonth(date: LocalDate): String = if (date.day == 1) s.firstOfMonth else "${date.day}"

    companion object {
        /** "21:00", "09:05", or "9:00 PM", "12:30 AM": AM/PM in every language, as in Haiti. Needs no language. */
        fun time(time: LocalTime, clock: ClockFormat): String =
            if (clock == ClockFormat.TwelveHour) "${(time.hour + 11) % 12 + 1}:${two(time.minute)} ${if (time.hour < 12) "AM" else "PM"}"
            else "${two(time.hour)}:${two(time.minute)}"

        private fun two(value: Int) = value.toString().padStart(2, '0')

        fun today(zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate = Clock.System.now().toLocalDateTime(zone).date
    }
}

val LocalDates = staticCompositionLocalOf { Dates(EnglishStrings.dates, ClockFormat.TwentyFourHour) }
