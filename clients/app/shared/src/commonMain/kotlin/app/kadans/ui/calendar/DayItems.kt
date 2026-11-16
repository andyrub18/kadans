package app.kadans.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kadans.api.model.TodoOccurrenceResponse
import app.kadans.calendar.PhoneEvent
import app.kadans.calendar.PhoneEvents
import app.kadans.i18n.LocalStrings
import app.kadans.ui.Dates
import app.kadans.ui.LocalDates
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** One line of a day's list: a todo's occurrence, or an event of the phone's calendar (ARCHITECTURE → "The phone's calendar"). */
sealed interface DayItem {
    data class Todo(val occurrence: TodoOccurrenceResponse) : DayItem

    data class Event(val event: PhoneEvent) : DayItem
}

/**
 * [day]'s list: the events of the whole day first, then todos and events together, by their hour. An event that began
 * on an earlier day counts from this day's start; at the same minute the todo comes first, being the person's own
 * thing to do.
 */
internal fun dayItems(day: LocalDate, occurrences: List<TodoOccurrenceResponse>, events: List<PhoneEvent>, zone: TimeZone): List<DayItem> {
    val dayStart = day.atStartOfDayIn(zone)
    val whole = events.filter { it.allDay }.map { DayItem.Event(it) }
    val timed: List<Pair<Instant, DayItem>> =
        occurrences.map { it.scheduledAt to DayItem.Todo(it) } + events.filter { !it.allDay }.map { maxOf(it.start, dayStart) to DayItem.Event(it) }
    return whole + timed.sortedWith(compareBy<Pair<Instant, DayItem>> { it.first }.thenBy { it.second is DayItem.Event }).map { it.second }
}

/** The days from [from] to [to] that have a todo or an event, in order, each with its list. */
internal fun daysWithItems(
    occurrences: List<TodoOccurrenceResponse>,
    events: List<PhoneEvent>,
    zone: TimeZone,
    from: LocalDate,
    to: LocalDate,
): List<Pair<LocalDate, List<DayItem>>> {
    val todos = occurrences.groupBy { it.scheduledAt.toLocalDateTime(zone).date }
    val theirs = PhoneEvents.byDay(events, from, to, zone)
    return (todos.keys + theirs.keys).sorted().map { day -> day to dayItems(day, todos[day].orEmpty(), theirs[day].orEmpty(), zone) }
}

/**
 * An event's hours as [day]'s list writes them: "14:00 – 15:00"; [allDay] for one that lasts the whole day, or covers
 * this day from end to end; "… – 09:00" and "22:00 – …" for the ends of one that crosses midnight; "14:00" alone for
 * one without a length.
 */
internal fun eventHours(event: PhoneEvent, day: LocalDate, zone: TimeZone, dates: Dates, allDay: String): String {
    if (event.allDay) return allDay
    val dayStart = day.atStartOfDayIn(zone)
    val dayEnd = day.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
    val beginsBefore = event.start < dayStart
    val endsAfter = event.end > dayEnd
    return when {
        beginsBefore && endsAfter -> allDay
        event.end == event.start -> dates.time(event.start, zone)
        else -> (if (beginsBefore) "…" else dates.time(event.start, zone)) + " – " + (if (endsAfter) "…" else dates.time(event.end, zone))
    }
}

/**
 * An event of the phone's calendar in a day's list: outlined where a todo is filled, with its calendar's colour down
 * the side, so that the two are never taken for each other. Its title is someone else's text (an invitation can come
 * from anyone): plain text, two lines at most. A tap opens it in the phone's calendar app; Kadans changes nothing in it.
 */
@Composable
fun PhoneEventCard(event: PhoneEvent, day: LocalDate, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalStrings.current.phoneCalendar
    val title = event.title.ifBlank { c.untitled }
    val hours = eventHours(event, day, TimeZone.currentSystemDefault(), LocalDates.current, c.allDay)
    val line = listOf(hours, event.calendar).filter { it.isNotBlank() }.joinToString(" · ")
    val side = event.color?.let { Color(it).copy(alpha = 1f) } ?: MaterialTheme.colorScheme.outline
    OutlinedCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "${c.eventLabel}: $title, $line" },
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(side))
            Column(Modifier.padding(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
