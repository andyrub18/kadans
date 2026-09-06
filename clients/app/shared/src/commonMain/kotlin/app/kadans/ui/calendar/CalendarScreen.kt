package app.kadans.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
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
import app.kadans.api.model.OccurrenceStatus
import app.kadans.api.model.TodoOccurrenceResponse
import app.kadans.i18n.LocalStrings
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun CalendarScreen(
    onOpenTodo: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: CalendarViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val s = LocalStrings.current
    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← " + s.back) }
            Text(
                s.monthNames[state.month - 1] + " " + state.year,
                style = MaterialTheme.typography.titleLarge,
            )
            Row {
                TextButton(onClick = viewModel::previous) { Text("‹") }
                TextButton(onClick = viewModel::next) { Text("›") }
            }
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

        if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 2.dp))

        state.cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { cell ->
                    DayCell(
                        cell = cell,
                        occurrences = state.byDay[cell.date].orEmpty(),
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

        state.selected?.let { selected ->
            Text(
                selected.toString(),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            if (state.selectedOccurrences.isEmpty()) {
                Text(s.nothingHere, style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.selectedOccurrences, key = { it.id ?: (it.todoId + it.scheduledAt.toString()) }) { occurrence ->
                    DayOccurrenceCard(occurrence, onClick = { onOpenTodo(occurrence.todoId) })
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    cell: MonthCell,
    occurrences: List<TodoOccurrenceResponse>,
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
            .aspectRatio(1f)
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
                    timeOf(occurrence),
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

private fun timeOf(occurrence: TodoOccurrenceResponse): String {
    val local = occurrence.scheduledAt.toLocalDateTime(TimeZone.currentSystemDefault())
    return local.hour.toString().padStart(2, '0') + ":" + local.minute.toString().padStart(2, '0')
}
