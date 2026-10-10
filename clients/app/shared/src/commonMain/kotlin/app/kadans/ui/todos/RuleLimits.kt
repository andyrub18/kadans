package app.kadans.ui.todos

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** What the server takes in a new rule (SharedKernel `RecurrenceSchedule`): neither the todo nor the budget form offers more. */
internal object RuleLimits {
    const val MAX_COUNT = 5_000
    const val MAX_YEARS = 10

    /**
     * The last day a rule starting on [start] may end on. The server allows ten years from the first instant, so an
     * end on that same day ten years later, at the end of the day the forms send, would be refused.
     */
    fun latestEnd(start: LocalDate): LocalDate = start.plus(MAX_YEARS, DateTimeUnit.YEAR).minus(1, DateTimeUnit.DAY)

    /** What a "how many times" field keeps of what is typed: digits, at most six (the limit's error shows above 5,000). */
    fun countInput(text: String): Int? = text.filter(Char::isDigit).take(6).toIntOrNull()
}
