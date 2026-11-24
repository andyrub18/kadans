package app.kadans.ui.home

import app.kadans.api.model.OccurrenceStatus
import app.kadans.api.model.TodoOccurrenceResponse
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * How far the day is: of its todos, how many are done, and the one to do next. A todo marked done on Home and not
 * yet sent ([ticked], see [Ticks]) counts as done at once. Cancelled ones are not the day's any more.
 */
data class TodayProgress(val done: Int, val total: Int, val next: TodoOccurrenceResponse?) {
    val fraction: Float get() = if (total == 0) 0f else done.toFloat() / total

    val allDone: Boolean get() = total > 0 && done == total

    companion object {
        fun of(
            occurrences: List<TodoOccurrenceResponse>,
            today: LocalDate,
            zone: TimeZone,
            now: Instant,
            ticked: Set<String> = emptySet(),
        ): TodayProgress {
            val theDay = occurrences.filter {
                it.status != OccurrenceStatus.Cancelled && it.scheduledAt.toLocalDateTime(zone).date == today
            }
            val pending = theDay.filter { !isDone(it, ticked) }.sortedBy { it.scheduledAt }
            // What comes next is the first still ahead; with none ahead, the earliest one left behind.
            val next = pending.firstOrNull { it.scheduledAt >= now } ?: pending.firstOrNull()
            return TodayProgress(done = theDay.size - pending.size, total = theDay.size, next = next)
        }

        fun isDone(occurrence: TodoOccurrenceResponse, ticked: Set<String>): Boolean =
            occurrence.status == OccurrenceStatus.Completed || occurrence.id in ticked
    }
}
