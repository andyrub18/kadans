package app.kadans.ui.calendar

import app.kadans.ui.ProgressLine
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import app.kadans.calendar.PhoneCalendar
import app.kadans.ui.settings.PhoneCalendarOffer
import org.koin.compose.koinInject
import app.kadans.api.model.OccurrenceStatus
import app.kadans.api.model.TodoOccurrenceResponse
import app.kadans.i18n.LocalStrings
import app.kadans.ui.Dates
import app.kadans.ui.LocalDates
import app.kadans.ui.shell.KadansIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import kotlinx.datetime.TimeZone
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun CalendarScreen(
    onOpenTodo: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CalendarViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val s = LocalStrings.current
    val phoneCalendar = koinInject<PhoneCalendar>()
    LaunchedEffect(Unit) { viewModel.refresh() }

    // Width-capped so desktop windows don't blow the day cells up; the day detail below shares
    // the height via weight, so tapping a date always shows its list on screen.
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxSize()) {
        // The destination's name is in the bar above; the month and its arrows lead the screen.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                (s.monthNames[state.month - 1] + " " + state.year).replaceFirstChar { it.titlecase() },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = viewModel::previous) { Icon(KadansIcons.Previous, contentDescription = s.nav.previousMonth) }
            IconButton(onClick = viewModel::next) { Icon(KadansIcons.Next, contentDescription = s.nav.nextMonth) }
        }

        Row(Modifier.fillMaxWidth()) {
            s.weekdayShort.forEach { day ->
                Text(
                    day,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }

        if (state.isLoading) ProgressLine(Modifier.fillMaxWidth().padding(vertical = 2.dp))

        state.cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { cell ->
                    DayCell(
                        cell = cell,
                        occurrences = state.byDay[cell.date].orEmpty(),
                        hasEvents = !state.eventsByDay[cell.date].isNullOrEmpty(),
                        isToday = cell.date == state.today,
                        isSelected = cell.date == state.selected,
                        onClick = { viewModel.select(cell.date) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (state.error != null || state.errorCode != null) {
            Text(
                s.errorFor(state.errorCode, state.error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        // Once, on a phone that has a calendar Kadans can read: show its events here?
        PhoneCalendarOffer(Modifier.padding(top = 8.dp), phoneCalendar)

        state.selected?.let { selected ->
            Text(
                LocalDates.current.long(selected, Dates.today()),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            // The day's todos, with the phone's own events among them where the person turned that on.
            val items = dayItems(selected, state.selectedOccurrences, state.selectedEvents, TimeZone.currentSystemDefault())
            if (items.isEmpty()) {
                Text(s.nothingHere, style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f, fill = false)) {
                items(
                    items,
                    key = {
                        when (it) {
                            is DayItem.Todo -> it.occurrence.id ?: (it.occurrence.todoId + it.occurrence.scheduledAt.toString())
                            is DayItem.Event -> "event ${it.event.id} ${it.event.start}"
                        }
                    },
                ) { item ->
                    when (item) {
                        is DayItem.Todo -> DayOccurrenceCard(item.occurrence, onClick = { onOpenTodo(item.occurrence.todoId) })
                        is DayItem.Event -> PhoneEventCard(item.event, selected, onClick = { phoneCalendar.open(item.event) })
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun DayCell(
    cell: MonthCell,
    occurrences: List<TodoOccurrenceResponse>,
    hasEvents: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        isToday -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    Column(
        modifier = modifier
            .height(46.dp)
            .padding(2.dp)
            .clip(MaterialTheme.shapes.small)
            .background(background)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            cell.date.day.toString(),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            color = if (cell.inMonth) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val pending = occurrences.any { it.status == OccurrenceStatus.Pending && !it.isPreview }
            val preview = occurrences.any { it.isPreview }
            val done = occurrences.any { it.status == OccurrenceStatus.Completed }
            if (pending) Dot(MaterialTheme.colorScheme.primary)
            if (preview) Dot(MaterialTheme.colorScheme.outline)
            if (done) Dot(MaterialTheme.colorScheme.tertiary)
            // Something in the phone's own calendar that day: a ring, where a todo is a full dot.
            if (hasEvents) Box(Modifier.size(5.dp).border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
        }
    }
}

@Composable
private fun Dot(color: androidx.compose.ui.graphics.Color) {
    Box(Modifier.size(5.dp).clip(CircleShape).background(color))
}

@Composable
private fun DayOccurrenceCard(occurrence: TodoOccurrenceResponse, onClick: () -> Unit) {
    val s = LocalStrings.current
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(occurrence.todoTitle, style = MaterialTheme.typography.titleSmall)
                Text(
                    LocalDates.current.time(occurrence.scheduledAt, TimeZone.currentSystemDefault()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (occurrence.isPreview) s.previewLabel else s.occurrenceStatusName(occurrence.status),
                style = MaterialTheme.typography.labelMedium,
                color = if (occurrence.isPreview) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
            )
        }
    }
}
