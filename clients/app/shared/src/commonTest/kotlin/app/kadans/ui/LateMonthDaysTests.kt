package app.kadans.ui

import app.kadans.api.model.Frequency
import app.kadans.i18n.EnglishStrings
import app.kadans.ui.todos.CreateTodoViewModel
import app.kadans.ui.todos.CreateTodoUiState
import app.kadans.ui.todos.DayRule
import app.kadans.ui.todos.LateMonthDays
import app.kadans.ui.todos.TodoMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

/** "The 15th and the 30th" is the 30th, or the last day of a month without one: what the rule sends and says. */
class LateMonthDaysTests {
    private val portAuPrince = TimeZone.of("America/Port-au-Prince")

    /** The days RFC 5545 gives a month of [length] days for these parts: BYMONTHDAY there, then BYSETPOS among them. */
    private fun daysIn(parts: LateMonthDays.Parts, length: Int): Set<Int> {
        val days = parts.byMonthDay.map { if (it < 0) length + 1 + it else it }.filter { it in 1..length }.distinct().sorted()
        val positions = parts.bySetPos ?: return days.toSet()
        return positions.mapNotNull { if (it > 0) days.getOrNull(it - 1) else days.getOrNull(days.size + it) }.toSet()
    }

    @Test
    fun every_set_of_days_falls_on_each_day_or_a_shorter_months_last_day() {
        val choices = listOf(1, 15, 27, 28, 29, 30, 31, -1)
        for (mask in 1 until (1 shl choices.size)) {
            val picked = choices.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
            for (shortest in 28..31) {
                val parts = LateMonthDays.parts(picked, shortest)
                for (length in shortest..31) {
                    val expected = picked.map { if (it == -1) length else minOf(it, length) }.toSet()
                    assertEquals(expected, daysIn(parts, length), "$picked in $length-day months (shortest $shortest): $parts")
                }
                // What the form sent comes back as what was picked (the 31st as the last day, the same in every month).
                parts.bySetPos?.let { positions ->
                    assertEquals(picked.map { if (it == 31) -1 else it }.toSet(), LateMonthDays.decode(parts.byMonthDay, positions), "$parts")
                }
            }
        }
    }

    @Test
    fun a_salary_on_the_15th_and_the_30th() {
        assertEquals(LateMonthDays.Parts(listOf(15, 28, 29, 30), listOf(1, -1)), LateMonthDays.parts(setOf(15, 30), 28))
        // The 31st, missing from some months, is simply their last day; days every month has stay as they are.
        assertEquals(LateMonthDays.Parts(listOf(15, -1)), LateMonthDays.parts(setOf(15, 31), 28))
        assertEquals(LateMonthDays.Parts(listOf(1, 15)), LateMonthDays.parts(setOf(1, 15), 28))
        assertEquals(LateMonthDays.Parts(listOf(30)), LateMonthDays.parts(setOf(30), 30)) // only months with a 30th
        // Not a rule the form makes: not read as one.
        assertEquals(null, LateMonthDays.decode(listOf(15, 28, 29, 30), listOf(-1, 1)))
        assertEquals(null, LateMonthDays.decode(listOf(1, 15), listOf(1)))
    }

    private val payday = CreateTodoUiState(
        title = "Payday", mode = TodoMode.Recurring, date = LocalDate(2027, 1, 4), time = LocalTime(9, 0),
        frequency = Frequency.Monthly, dayRule = DayRule.ByDate, monthDays = setOf(15, 30),
    )

    @Test
    fun the_form_sends_it_and_says_it() {
        val rule = CreateTodoViewModel.buildRecurring(payday, portAuPrince).recurrenceRule
        assertEquals(listOf(15, 28, 29, 30), rule.byMonthDay)
        assertEquals(listOf(1, -1), rule.bySetPos)
        assertEquals(30, payday.missingMonthDay)
        assertEquals(LocalDate(2027, 1, 15), payday.firstDate)
        // From the 16th of February, the next one is the 28th: February's last day.
        assertEquals(LocalDate(2027, 2, 28), payday.copy(date = LocalDate(2027, 2, 16)).firstDate)
        assertEquals(
            "Every month · on days 15, 30 (or the last day of a shorter month) · 09:00",
            CreateTodoViewModel.ruleInWords(payday, EnglishStrings, portAuPrince, now = kotlin.time.Instant.parse("2027-01-01T00:00:00Z")),
        )
    }

    @Test
    fun the_30th_of_february_is_its_last_day_and_several_months_are_each_months() {
        val february = payday.copy(frequency = Frequency.Yearly, months = setOf(2), monthDays = setOf(30))
        assertFalse(february.neverFalls)
        assertEquals(LocalDate(2027, 2, 28), february.firstDate)
        val sent = CreateTodoViewModel.buildRecurring(february, portAuPrince).recurrenceRule
        assertEquals(Frequency.Yearly, sent.frequency) // one month: a yearly rule counts set positions within it
        assertEquals(listOf(28, 29, 30), sent.byMonthDay)
        assertEquals(listOf(-1), sent.bySetPos)

        // In January and February, each month's 30th or last day: a monthly rule kept to them, so every year only.
        val two = february.copy(months = setOf(1, 2))
        assertEquals(Frequency.Monthly, CreateTodoViewModel.buildRecurring(two, portAuPrince).recurrenceRule.frequency)
        assertTrue(two.copy(interval = 2).severalMonthsNeedEveryYear)
        // In months that all have a 30th, nothing moves.
        val long = payday.copy(frequency = Frequency.Yearly, months = setOf(1, 3), monthDays = setOf(30))
        assertEquals(null, long.missingMonthDay)
        assertEquals(null, CreateTodoViewModel.buildRecurring(long, portAuPrince).recurrenceRule.bySetPos)
    }
}
