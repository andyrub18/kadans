package app.kadans.ui.todos

import app.kadans.api.model.ApiDayOfWeek
import app.kadans.api.model.CreateRecurrenceRule
import app.kadans.api.model.Frequency
import app.kadans.api.model.RecurrenceRuleResponse
import app.kadans.i18n.StringsCatalog
import app.kadans.ui.mondayFirstDays
import app.kadans.ui.toApi
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime

/**
 * A todo's rule and times in words, on the todo screen and under the form as it is filled: "Every week · Mon–Fri ·
 * 07:00", "Every month · the last weekday · 18:00", "Every year · January, July · on day 1 · 09:00". Every rule the
 * app creates reads in full. A rule made elsewhere with a part this does not word (BYWEEKNO, BYYEARDAY, "the first
 * Monday" written 1MO) shows as its RRULE, as it always did.
 */
internal object RuleSummary {
    /** The parts this words, with RFC 5545's names. */
    data class Parts(
        val frequency: Frequency,
        val interval: Int,
        val byDay: List<ApiDayOfWeek>? = null,
        val byMonthDay: List<Int>? = null,
        val bySetPos: List<Int>? = null,
        val byMonth: List<Int>? = null,
        val byHour: List<Int>? = null,
        val byMinute: List<Int>? = null,
        val count: Int? = null,
        val until: Instant? = null,
    )

    fun describe(rule: RecurrenceRuleResponse, s: StringsCatalog, deviceZone: TimeZone): String {
        val zone = runCatching { TimeZone.of(rule.timeZoneId) }.getOrNull()
        val words = zone?.let {
            if (rule.isOneTime) s.oneTime + " · " + moment(rule.startDate, it, s)
            else parse(rule)?.let { parts -> words(parts, rule.startDate.toLocalDateTime(it), it, s) }
        } ?: rule.rrule
        // The rule's times are its own zone's wall clock: say which when this device is elsewhere.
        return if (rule.timeZoneId == deviceZone.id) words else "$words · ${rule.timeZoneId}"
    }

    /** The rule the form is about to send, in words (in its zone, the device's); null if a part cannot be worded. */
    fun describe(rule: CreateRecurrenceRule, s: StringsCatalog): String? {
        val zone = rule.timeZone?.let { runCatching { TimeZone.of(it) }.getOrNull() } ?: return null
        val parts = Parts(
            rule.frequency, rule.interval, rule.byDayOfWeek, rule.byMonthDay, rule.bySetPos, rule.byMonth,
            rule.byHour, rule.byMinute, rule.count, rule.until,
        )
        return words(parts, rule.startDate.toLocalDateTime(zone), zone, s)
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

    /** "the second Tuesday", "the last weekday". */
    fun onThe(ordinal: Ordinal, kind: DayKind, s: StringsCatalog): String =
        s.repeat.onThe(s.repeat.ordinals[ordinal.ordinal], kindName(kind, s)) // Ordinal's order is the list's: first … last

    fun kindName(kind: DayKind, s: StringsCatalog): String = when (kind) {
        DayKind.Day -> s.repeat.kindDay
        DayKind.Weekday -> s.repeat.kindWeekday
        DayKind.WeekendDay -> s.repeat.kindWeekendDay
        else -> s.repeat.weekdayNames[mondayFirstDays.indexOf(kind.days.single())]
    }

    private fun parse(rule: RecurrenceRuleResponse): Parts? {
        val parts = rule.rrule.removePrefix("RRULE:").split(';').filter { it.isNotBlank() }.associate { part ->
            part.substringBefore('=').uppercase() to part.substringAfter('=', "")
        }
        if (!parts.keys.all { it in WORDED }) return null
        return Parts(
            frequency = rule.frequency,
            interval = rule.interval,
            byDay = parts["BYDAY"]?.let { list -> list.split(',').map { DAY_CODES[it.trim().uppercase()] ?: return null } },
            byMonthDay = parts["BYMONTHDAY"]?.let { numbers(it, -31..31) ?: return null },
            bySetPos = parts["BYSETPOS"]?.let { numbers(it, -366..366) ?: return null },
            byMonth = parts["BYMONTH"]?.let { numbers(it, 1..12) ?: return null },
            byHour = parts["BYHOUR"]?.let { numbers(it, 0..23) ?: return null },
            byMinute = parts["BYMINUTE"]?.let { numbers(it, 0..59) ?: return null },
            count = rule.count,
            until = rule.until,
        )
    }

    /** Null when the rule has a part, or a mix of parts, this cannot word. */
    private fun words(rule: Parts, start: LocalDateTime, zone: TimeZone, s: StringsCatalog): String? {
        val months = rule.byMonth?.distinct()?.sorted()
        // A monthly rule kept to some months, every month: how the form says "the second Sunday" of several months.
        val yearlyInMonths = rule.frequency == Frequency.Monthly && months != null && rule.interval == 1
        val words = mutableListOf(
            if (yearlyInMonths) s.repeat.every(Frequency.Yearly, 1) else s.repeat.every(rule.frequency, rule.interval),
        )
        when (rule.frequency) {
            // Minute and hourly rules take no day or time parts (RecurrenceSchedule refuses them).
            Frequency.Minutely, Frequency.Hourly ->
                if (listOf(rule.byDay, rule.byMonthDay, rule.bySetPos, rule.byMonth, rule.byHour, rule.byMinute).any { it != null }) return null
            Frequency.Daily -> {
                if (rule.byMonthDay != null || rule.bySetPos != null || months != null) return null
                rule.byDay?.let { words += dayList(it.toSet(), s) }
            }
            Frequency.Weekly -> {
                if (rule.byMonthDay != null || rule.bySetPos != null || months != null) return null
                words += dayList((rule.byDay ?: listOf(start.date.dayOfWeek.toApi())).toSet(), s)
            }
            Frequency.Monthly, Frequency.Yearly -> {
                // Without months, a yearly rule's days spread over the whole year: not one the app makes.
                val dayParts = rule.byDay != null || rule.byMonthDay != null || rule.bySetPos != null
                if (rule.frequency == Frequency.Yearly && months == null && dayParts) return null
                months?.let { words += it.joinToString(", ") { month -> s.monthNames[month - 1] } }
                words += when {
                    rule.bySetPos != null -> {
                        if (rule.byMonthDay != null) return null
                        val ordinal = Ordinal.ofSetPos(rule.bySetPos.singleOrNull() ?: return null) ?: return null
                        onThe(ordinal, DayKind.ofDays(rule.byDay ?: return null) ?: return null, s)
                    }
                    rule.byDay != null -> return null // every Monday of the month: not one the app makes
                    rule.byMonthDay != null -> monthDays(rule.byMonthDay, s) ?: return null
                    rule.frequency == Frequency.Yearly && months == null -> "${start.date.day} ${s.monthNames[start.date.month.ordinal]}"
                    else -> s.repeat.monthDay(start.date.day)
                }
            }
        }
        if (rule.frequency != Frequency.Minutely && rule.frequency != Frequency.Hourly) {
            val hours = rule.byHour ?: listOf(start.hour)
            val minutes = rule.byMinute ?: listOf(start.minute)
            words += hours.flatMap { hour -> minutes.map { LocalTime(hour, it) } }.distinct().sorted().joinToString(", ") { it.clock() }
        }
        rule.count?.let { words += s.repeat.count(it) }
        rule.until?.let { words += s.repeat.until(untilLabel(it, zone)) }
        return words.joinToString(" · ")
    }

    /** "on day 15", "on the last day", "on days 1, 15, last". Null for other days counted from the month's end. */
    private fun monthDays(days: List<Int>, s: StringsCatalog): String? {
        if (days.any { it == 0 || it < -1 }) return null
        val sorted = sortedMonthDays(days.distinct())
        return when {
            sorted == listOf(-1) -> s.repeat.onLastDay
            sorted.size == 1 -> s.repeat.monthDay(sorted.single())
            else -> s.repeat.monthDays(sorted.joinToString(", ") { if (it == -1) s.repeat.ordinals.last() else "$it" })
        }
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

    private val WORDED = setOf(
        "FREQ", "INTERVAL", "COUNT", "UNTIL", "WKST", "BYDAY", "BYMONTHDAY", "BYSETPOS", "BYMONTH", "BYHOUR", "BYMINUTE",
    )

    private val DAY_CODES = mapOf(
        "MO" to ApiDayOfWeek.Monday, "TU" to ApiDayOfWeek.Tuesday, "WE" to ApiDayOfWeek.Wednesday,
        "TH" to ApiDayOfWeek.Thursday, "FR" to ApiDayOfWeek.Friday, "SA" to ApiDayOfWeek.Saturday,
        "SU" to ApiDayOfWeek.Sunday,
    )
}
