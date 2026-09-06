package app.kadans.ui.calendar

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

data class MonthCell(val date: LocalDate, val inMonth: Boolean)

/**
 * A fixed 6×7 Monday-first grid for the given month — always 42 cells, so the calendar
 * never changes height while paging months.
 */
internal fun monthGrid(year: Int, month: Int): List<MonthCell> {
    val first = LocalDate(year, month, 1)
    val start = first.minus(first.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
    return (0 until 42).map { offset ->
        val date = start.plus(offset, DateTimeUnit.DAY)
        MonthCell(date, inMonth = date.year == year && date.month == first.month)
    }
}

internal fun previousMonth(year: Int, month: Int): Pair<Int, Int> =
    if (month == 1) year - 1 to 12 else year to month - 1

internal fun nextMonth(year: Int, month: Int): Pair<Int, Int> =
    if (month == 12) year + 1 to 1 else year to month + 1
