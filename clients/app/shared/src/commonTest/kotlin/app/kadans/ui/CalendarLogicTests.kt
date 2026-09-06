package app.kadans.ui

import app.kadans.ui.calendar.monthGrid
import app.kadans.ui.calendar.nextMonth
import app.kadans.ui.calendar.previousMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

class CalendarLogicTests {
    @Test
    fun gridIsAlwaysSixMondayFirstWeeks() {
        // September 2026 starts on a Tuesday, so the grid begins Monday August 31.
        val cells = monthGrid(2026, 9)
        assertEquals(42, cells.size)
        assertEquals(LocalDate(2026, 8, 31), cells.first().date)
        assertEquals(LocalDate(2026, 10, 11), cells.last().date)
        assertEquals(30, cells.count { it.inMonth })
    }

    @Test
    fun monthStartingOnMondayHasNoLeadingPadding() {
        // June 2026 starts on a Monday.
        val cells = monthGrid(2026, 6)
        assertEquals(LocalDate(2026, 6, 1), cells.first().date)
        assertTrue(cells.first().inMonth)
    }

    @Test
    fun monthPagingWrapsAcrossYears() {
        assertEquals(2025 to 12, previousMonth(2026, 1))
        assertEquals(2027 to 1, nextMonth(2026, 12))
        assertEquals(2026 to 5, previousMonth(2026, 6))
        assertEquals(2026 to 7, nextMonth(2026, 6))
    }
}
