package app.kadans.ui.todos

import app.kadans.api.model.ApiDayOfWeek
import app.kadans.ui.mondayFirstDays
import app.kadans.ui.toApi
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus

/**
 * How a monthly or yearly rule picks its day, as calendar apps offer it: by date ("the 1st and the 15th", "the last
 * day": RFC 5545 BYMONTHDAY) or by day of the week ("the second Tuesday", "the last weekday": BYDAY with BYSETPOS).
 */
enum class DayRule { ByDate, ByWeekday }

/** Which of the month's matching days (BYSETPOS): the first to the fifth, or the last. */
enum class Ordinal(val setPos: Int) {
    First(1),
    Second(2),
    Third(3),
    Fourth(4),
    Fifth(5),
    Last(-1),
    ;

    companion object {
        /** Where [date] falls among its month's days of the same weekday; a fifth one is always the last. */
        fun of(date: LocalDate): Ordinal = when ((date.day - 1) / 7) {
            0 -> First
            1 -> Second
            2 -> Third
            3 -> Fourth
            else -> Last
        }

        fun ofSetPos(position: Int): Ordinal? = entries.firstOrNull { it.setPos == position }
    }
}

/** What "the second …" counts (BYDAY): one day of the week, any day, a weekday (Monday to Friday) or a weekend day. */
enum class DayKind(val days: List<ApiDayOfWeek>) {
    Monday(listOf(ApiDayOfWeek.Monday)),
    Tuesday(listOf(ApiDayOfWeek.Tuesday)),
    Wednesday(listOf(ApiDayOfWeek.Wednesday)),
    Thursday(listOf(ApiDayOfWeek.Thursday)),
    Friday(listOf(ApiDayOfWeek.Friday)),
    Saturday(listOf(ApiDayOfWeek.Saturday)),
    Sunday(listOf(ApiDayOfWeek.Sunday)),
    Day(mondayFirstDays),
    Weekday(mondayFirstDays.take(5)),
    WeekendDay(mondayFirstDays.drop(5)),
    ;

    companion object {
        /** The kinds that are not one day of the week, offered after the seven days. */
        val Groups = listOf(Day, Weekday, WeekendDay)

        fun of(day: ApiDayOfWeek): DayKind = entries.first { it.days == listOf(day) }

        /** The kind whose days are exactly [days], whatever their order. */
        fun ofDays(days: Collection<ApiDayOfWeek>): DayKind? = entries.firstOrNull { it.days.toSet() == days.toSet() }
    }
}

internal fun lastDayOfMonth(date: LocalDate): Int =
    LocalDate(date.year, date.month.number, 1).plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY).day

/** Whether [date] is [ordinal] [kind] of its month: "the second Tuesday", "the last weekday". */
internal fun isNthOfMonth(date: LocalDate, ordinal: Ordinal, kind: DayKind): Boolean {
    if (date.dayOfWeek.toApi() !in kind.days) return false
    val lastDay = lastDayOfMonth(date)
    if (kind.days.size == 1) {
        // One day of the week: the nth falls on days 7n−6 to 7n, the last in the month's final seven days.
        return if (ordinal == Ordinal.Last) date.day + 7 > lastDay else (date.day - 1) / 7 + 1 == ordinal.setPos
    }
    val first = LocalDate(date.year, date.month.number, 1)
    val matching = (0 until lastDay).map { first.plus(it, DateTimeUnit.DAY) }.filter { it.dayOfWeek.toApi() in kind.days }
    return if (ordinal == Ordinal.Last) matching.last() == date else matching.getOrNull(ordinal.setPos - 1) == date
}

/**
 * Whether [date] is one of the month days picked: 1–31, -1 the last day. A day its month lacks (the 30th in February)
 * falls on that month's last day ([LateMonthDays]).
 */
internal fun isMonthDay(date: LocalDate, days: Set<Int>): Boolean =
    date.day in days || (date.day == lastDayOfMonth(date) && days.any { it == -1 || it > date.day })

/**
 * Days of the month as a salary means them: "the 15th and the 30th" is the 30th, or the last day of a month without one
 * (February). RFC 5545 skips a month that lacks the day, so such a rule says it with set positions: BYMONTHDAY takes
 * the days up to the 28th plus the 28th to the latest late day as candidates, and BYSETPOS keeps the early days, each
 * late day but the latest at its place among the candidates, and the last candidate. A month too short for a late day
 * lacks its candidate, and its last day stands for it. The 31st missing somewhere is simply "the last day" (-1).
 */
internal object LateMonthDays {
    data class Parts(val byMonthDay: List<Int>, val bySetPos: List<Int>? = null)

    /** The parts for [days] in months whose shortest has [shortest] days (28 for February). */
    fun parts(days: Set<Int>, shortest: Int): Parts {
        if (days.none { it > shortest }) return Parts(sortedMonthDays(days))
        val asLast = days.map { if (it == 31) -1 else it }.toSet()
        if (asLast.none { it > shortest }) return Parts(sortedMonthDays(asLast))
        return encode(asLast)
    }

    private fun encode(days: Set<Int>): Parts {
        val early = days.filter { it in 1..28 }.sorted()
        val late = days.map { if (it == -1) 31 else it }.filter { it > 28 }.distinct().sorted()
        val byMonthDay = (early + (28..late.last())).distinct().sorted()
        val twentyEighth = (early + 28).distinct().size // the 28th's position among the month's days
        return Parts(byMonthDay, early.indices.map { it + 1 } + late.dropLast(1).map { twentyEighth + it - 28 } + -1)
    }

    /** The days a rule made by [parts] was picked with (the 31st as -1); null for any other BYMONTHDAY with BYSETPOS. */
    fun decode(byMonthDay: List<Int>, bySetPos: List<Int>): Set<Int>? {
        val sorted = byMonthDay.distinct().sorted()
        if (sorted.isEmpty() || sorted.any { it !in 1..31 }) return null
        // In a 31-day month every candidate is there: the positions give back the days picked.
        val picked = bySetPos.map { position ->
            (if (position > 0) sorted.getOrNull(position - 1) else sorted.getOrNull(sorted.size + position)) ?: return null
        }.map { if (it == 31) -1 else it }.toSet()
        return picked.takeIf { it.any { day -> day == -1 || day > 28 } && encode(it) == Parts(sorted, bySetPos) }
    }
}

/** Positives ascending, then the last day: the order the rule and its words list them in. */
internal fun sortedMonthDays(days: Collection<Int>): List<Int> = days.filter { it > 0 }.sorted() + days.filter { it < 0 }.sortedDescending()
