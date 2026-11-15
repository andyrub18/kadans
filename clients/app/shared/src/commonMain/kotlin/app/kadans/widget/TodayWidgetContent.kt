package app.kadans.widget

import app.kadans.api.model.ClockFormat
import app.kadans.i18n.StringsCatalog
import app.kadans.i18n.WidgetStrings
import app.kadans.ui.Dates
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable

/**
 * One pending time of a todo, as the widget keeps it between fetches. [occurrenceId]: what "Done" marks; null for a
 * time the server only computed (far ahead: never today's or tomorrow's), which has no "Done".
 */
@Serializable
data class WidgetEntry(val todoId: String, val title: String, val at: Instant, val occurrenceId: String? = null)

/** What the widget cannot show the day for, each with one sentence and a tap that opens the app. */
enum class WidgetGap {
    /** Nobody is signed in on this phone. */
    SignedOut,

    /** The account has no subscription for phones: the app itself is behind its paywall. */
    Locked,

    /** Signed in, and nothing fetched yet (placed while offline). */
    NotLoaded,
}

/** One line under the widget's header. */
sealed interface WidgetLine {
    /**
     * A todo's time: the hour as the account's clock writes it, and the title. A tap on them opens the todo.
     * [done]: the occurrence a tap on the line's circle marks done. Today's lines have one; tomorrow's have none (as a
     * reminder offers "Done" only once its todo starts). [undo]: the circle was tapped a moment ago: the line is struck
     * through and offers "Undo" until the completion is sent.
     */
    data class Todo(
        val todoId: String,
        val time: String,
        val title: String,
        val done: String? = null,
        val undo: Boolean = false,
    ) : WidgetLine

    /** A sentence in place of todos ("Nothing left today", "Sign in to see your day"). */
    data class Note(val text: String) : WidgetLine

    /** What the lines below belong to ("Tomorrow"). */
    data class Label(val text: String) : WidgetLine

    /** The todos that did not fit ("3 more"). */
    data class More(val text: String) : WidgetLine
}

/** The widget as it is drawn: "Today" and the date above, then as many [lines] as its height holds. */
data class TodayWidgetContent(val title: String, val date: String, val lines: List<WidgetLine>) {
    companion object {
        /**
         * The day's pending todos, soonest first, earlier ones still pending included; with none left, it says so.
         * Then tomorrow's, under their label, when there is room left for it. [capacity]: the lines that fit under the
         * header; todos that do not fit give their last line to "N more". "Today" is this phone's day ([zone]), as on
         * the app's screens; the words are the app's language ([catalog]) and the hours the account's [clock].
         *
         * [doneAt]: the occurrences whose circle was tapped, each with the moment its completion is sent. Before it,
         * the line is struck through with "Undo"; from then on the line is gone, sent or still waiting for a network.
         */
        fun of(
            entries: List<WidgetEntry>?,
            gap: WidgetGap?,
            now: Instant,
            zone: TimeZone,
            catalog: StringsCatalog,
            clock: ClockFormat,
            capacity: Int,
            doneAt: Map<String, Instant> = emptyMap(),
        ): TodayWidgetContent {
            val strings: WidgetStrings = catalog.widget
            val dates = Dates(catalog.dates, clock)
            val today = now.toLocalDateTime(zone).date
            val head = { lines: List<WidgetLine> -> TodayWidgetContent(catalog.dates.today, dates.short(today, today), lines) }
            val room = capacity.coerceAtLeast(1)
            when (gap) {
                WidgetGap.SignedOut -> return head(listOf(WidgetLine.Note(strings.signedOut)))
                WidgetGap.Locked -> return head(listOf(WidgetLine.Note(strings.locked)))
                WidgetGap.NotLoaded -> return head(listOf(WidgetLine.Note(strings.notLoaded)))
                null -> Unit
            }
            if (entries == null) return head(listOf(WidgetLine.Note(strings.notLoaded)))

            val sorted = entries
                .filterNot { entry -> doneAt[entry.occurrenceId]?.let { it <= now } == true }
                .sortedWith(compareBy({ it.at }, { it.title }))
            fun todosOf(day: kotlinx.datetime.LocalDate, withDone: Boolean) = sorted
                .filter { it.at.toLocalDateTime(zone).date == day }
                .map {
                    WidgetLine.Todo(
                        it.todoId, dates.time(it.at, zone), it.title,
                        done = it.occurrenceId.takeIf { withDone },
                        undo = withDone && it.occurrenceId in doneAt,
                    )
                }

            val todays = todosOf(today, withDone = true)
            val lines = mutableListOf<WidgetLine>()
            if (todays.isEmpty()) lines += WidgetLine.Note(strings.nothingLeft) else lines += fit(todays, room, strings)
            // Tomorrow's under them, when today's all fit and its label and at least one of them still do: a widget
            // with one todo left today is not left three quarters empty. A label over nothing but "2 more" names no
            // todo: with room for one line only, it must hold tomorrow's only todo.
            val tomorrows = todosOf(today.plus(1, DateTimeUnit.DAY), withDone = false)
            val under = room - lines.size - 1
            if (tomorrows.isNotEmpty() && todays.size <= room && (tomorrows.size <= under || under >= 2)) {
                lines += WidgetLine.Label(catalog.dates.tomorrow)
                lines += fit(tomorrows, under, strings)
            }
            return head(lines)
        }

        private fun fit(todos: List<WidgetLine.Todo>, room: Int, strings: WidgetStrings): List<WidgetLine> =
            if (todos.size <= room) todos
            else todos.take(room - 1) + WidgetLine.More(strings.more(todos.size - (room - 1)))
    }
}
