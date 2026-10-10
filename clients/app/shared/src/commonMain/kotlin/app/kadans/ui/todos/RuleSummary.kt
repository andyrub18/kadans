package app.kadans.ui.todos

import app.kadans.api.model.ApiDayOfWeek
import app.kadans.api.model.Frequency
import app.kadans.api.model.RecurrenceRuleResponse
import app.kadans.i18n.StringsCatalog
import app.kadans.ui.mondayFirstDays
import app.kadans.ui.toApi
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime

/**
 * A todo's rule and times in words, for the detail screen: "Every week · Mon–Fri · 07:00", "Mon 2026-10-12 07:00".
 * Every rule the app creates reads in full. A rule made elsewhere with a part this does not word (BYSETPOS, BYMONTH,
 * "the first Monday") shows as its RRULE, as it always did.
 */
internal object RuleSummary {
    fun describe(rule: RecurrenceRuleResponse, s: StringsCatalog, deviceZone: TimeZone): String {
        val zone = runCatching { TimeZone.of(rule.timeZoneId) }.getOrNull()
        val words = zone?.let { words(rule, it, s) } ?: rule.rrule
        // The rule's times are its own zone's wall clock: say which when this device is elsewhere.
        return if (rule.timeZoneId == deviceZone.id) words else "$words · ${rule.timeZoneId}"
    }

    /** "Mon 2026-10-12 07:00" in [zone]: an occurrence as the person lives it, not as UTC. */
    fun moment(at: Instant, zone: TimeZone, s: StringsCatalog): String {
        val local = at.toLocalDateTime(zone)
        return day(local.date, s) + " " + local.time.clock()
    }

    /** "Mon 2026-10-12". */
    fun day(date: LocalDate, s: StringsCatalog): String = s.weekdayShort[date.dayOfWeek.isoDayNumber - 1] + " " + date

    /** "Mon–Fri", "Mon, Wed, Fri", "Sat, Sun": three days in a row or more read as a range. */
    fun dayList(days: Set<ApiDayOfWeek>, s: StringsCatalog): String {
        val runs = mutableListOf<MutableList<Int>>()
        mondayFirstDays.indices.filter { mondayFirstDays[it] in days }.forEach { index ->
            val run = runs.lastOrNull()
            if (run != null && run.last() == index - 1) run += index else runs += mutableListOf(index)
        }
        return runs.joinToString(", ") { run ->
            if (run.size >= 3) s.weekdayShort[run.first()] + "–" + s.weekdayShort[run.last()]
            else run.joinToString(", ") { s.weekdayShort[it] }
        }
    }

    /** Null when the rule has a part this cannot word. */
    private fun words(rule: RecurrenceRuleResponse, zone: TimeZone, s: StringsCatalog): String? {
        if (rule.isOneTime) return s.oneTime + " · " + moment(rule.startDate, zone, s)

        val parts = rule.rrule.removePrefix("RRULE:").split(';').filter { it.isNotBlank() }.associate { part ->
            part.substringBefore('=').uppercase() to part.substringAfter('=', "")
        }
        if (!parts.keys.all { it in WORDED }) return null
        val days = parts["BYDAY"]?.let { list -> list.split(',').map { DAY_CODES[it.trim().uppercase()] ?: return null } }
        val monthDays = parts["BYMONTHDAY"]?.let { numbers(it, 1..31) ?: return null }
        val start = rule.startDate.toLocalDateTime(zone)

        val words = mutableListOf(s.repeat.every(rule.frequency, rule.interval))
        when (rule.frequency) {
            // Minute and hourly rules take no day or time parts (RecurrenceSchedule refuses them).
            Frequency.Minutely, Frequency.Hourly ->
                if (days != null || monthDays != null || "BYHOUR" in parts || "BYMINUTE" in parts) return null
            Frequency.Daily -> {
                if (monthDays != null) return null
                if (days != null) words += dayList(days.toSet(), s)
            }
            Frequency.Weekly -> {
                if (monthDays != null) return null
                words += dayList((days ?: listOf(start.dayOfWeek.toApi())).toSet(), s)
            }
            Frequency.Monthly -> {
                if (days != null || (monthDays != null && monthDays.size != 1)) return null
                words += s.repeat.monthDay(monthDays?.single() ?: start.day)
            }
            Frequency.Yearly -> {
                if (days != null || monthDays != null) return null
                words += "${start.day} ${s.monthNames[start.month.ordinal]}"
            }
        }
        if (rule.frequency != Frequency.Minutely && rule.frequency != Frequency.Hourly) {
            val hours = parts["BYHOUR"]?.let { numbers(it, 0..23) ?: return null } ?: listOf(start.hour)
            val minutes = parts["BYMINUTE"]?.let { numbers(it, 0..59) ?: return null } ?: listOf(start.minute)
            words += hours.flatMap { hour -> minutes.map { LocalTime(hour, it) } }.distinct().sorted().joinToString(", ") { it.clock() }
        }
        rule.count?.let { words += s.repeat.count(it) }
        rule.until?.let { words += s.repeat.until(untilLabel(it, zone)) }
        return words.joinToString(" · ")
    }

    /** The last day, with its time unless it is the end of that day (what the form sends without a time). */
    private fun untilLabel(until: Instant, zone: TimeZone): String {
        val local = until.toLocalDateTime(zone)
        return if (local.time.hour == END_OF_DAY.hour && local.time.minute == END_OF_DAY.minute) "${local.date}"
        else "${local.date} ${local.time.clock()}"
    }

    private fun numbers(list: String, range: IntRange): List<Int>? =
        list.split(',').map { it.trim().toIntOrNull()?.takeIf { n -> n in range } ?: return null }

    private fun LocalTime.clock(): String = hour.toString().padStart(2, '0') + ":" + minute.toString().padStart(2, '0')

    private val WORDED = setOf("FREQ", "INTERVAL", "COUNT", "UNTIL", "WKST", "BYDAY", "BYMONTHDAY", "BYHOUR", "BYMINUTE")

    private val DAY_CODES = mapOf(
        "MO" to ApiDayOfWeek.Monday, "TU" to ApiDayOfWeek.Tuesday, "WE" to ApiDayOfWeek.Wednesday,
        "TH" to ApiDayOfWeek.Thursday, "FR" to ApiDayOfWeek.Friday, "SA" to ApiDayOfWeek.Saturday,
        "SU" to ApiDayOfWeek.Sunday,
    )
}
