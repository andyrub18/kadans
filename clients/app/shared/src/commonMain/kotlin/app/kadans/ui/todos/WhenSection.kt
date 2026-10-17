package app.kadans.ui.todos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kadans.i18n.LocalStrings
import app.kadans.ui.DateRange
import app.kadans.ui.PickerField
import app.kadans.ui.PickerKind
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * When a todo happens: its date and time and, for a recurring one, how it repeats and ends ([RepeatSection]), with the
 * rule read back in words. The create form and the edit screen both use it; [update] changes [state]. Times are wall
 * clock in [zone]: the device's for a new todo, the rule's own when one is edited.
 */
@Composable
internal fun WhenSection(
    state: CreateTodoUiState,
    update: ((CreateTodoUiState) -> CreateTodoUiState) -> Unit,
    zone: TimeZone = TimeZone.currentSystemDefault(),
) {
    val s = LocalStrings.current
    val now = Clock.System.now()
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }

    // Tapped anywhere, the fields open their pickers: the value keeps the room a "Pick" button used to take.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        PickerField(
            value = state.date?.toString() ?: "",
            label = if (state.mode == TodoMode.OneTime) s.dueDate else s.firstOn,
            kind = PickerKind.Date,
            onPick = { pickingDate = true },
            placeholder = s.pickADate,
            modifier = Modifier.weight(1.4f),
        )
        if (state.times.isEmpty()) {
            PickerField(
                value = state.time.formatted(),
                label = s.timeLabel,
                kind = PickerKind.Time,
                onPick = { pickingTime = true },
                modifier = Modifier.weight(1f),
            )
        }
    }
    if (CreateTodoViewModel.startPassed(state, zone, now)) {
        Text(s.repeat.startPassed, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }

    if (state.mode == TodoMode.Recurring) {
        RepeatSection(state, update)

        // The rule as it will be sent, in words, and its first moment when that is not the date and time picked.
        val words = remember(state, s) { CreateTodoViewModel.ruleInWords(state, s, zone, now) }
        if (words != null) {
            Text(words, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        val first = CreateTodoViewModel.firstStart(state, zone, now)
        val picked = state.date?.let { LocalDateTime(it, CreateTodoViewModel.effectiveTime(state)) }
        if (first != null && first != picked) {
            Text(
                s.repeat.firstTime(RuleSummary.day(first.date, s) + " " + first.time.formatted()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.neverFalls) {
            Text(s.repeat.neverFalls, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }

    if (pickingDate) {
        // Only dates the server accepts: a start from today on (the end's picker is the rule's, in RepeatSection).
        val today = remember { Clock.System.todayIn(zone) }
        val pickerState = rememberDatePickerState(selectableDates = DateRange(today, null))
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val picked = LocalDate.fromEpochDays((millis / 86_400_000L).toInt())
                        update { it.copy(date = picked) }
                    }
                    pickingDate = false
                }) { Text(s.ok) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(s.cancel) } },
        ) { DatePicker(state = pickerState) }
    }

    if (pickingTime) {
        TimeDialog(state.time, onPick = { picked -> update { it.copy(time = picked) } }, onDismiss = { pickingTime = false })
    }
}

/** A clock to pick a wall-clock time, starting at [initial]. */
@Composable
internal fun TimeDialog(initial: LocalTime, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val timeState = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onPick(LocalTime(timeState.hour, timeState.minute))
                onDismiss()
            }) { Text(s.ok) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
        text = { TimePicker(state = timeState) },
    )
}

internal fun LocalTime.formatted(): String =
    hour.toString().padStart(2, '0') + ":" + minute.toString().padStart(2, '0')
