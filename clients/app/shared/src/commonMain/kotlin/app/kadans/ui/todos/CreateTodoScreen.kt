package app.kadans.ui.todos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kadans.api.model.Frequency
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import app.kadans.i18n.LocalStrings
import org.koin.compose.viewmodel.koinViewModel

private enum class TimeTarget { Start, ExtraTime }

private enum class DateTarget { Start, Until }

@Composable
fun CreateTodoScreen(
    onCreated: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: CreateTodoViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val s = LocalStrings.current
    var dateTarget by remember { mutableStateOf<DateTarget?>(null) }
    var timeTarget by remember { mutableStateOf<TimeTarget?>(null) }

    LaunchedEffect(viewModel) { viewModel.created.collect { onCreated(it) } }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(s.newTodo, style = MaterialTheme.typography.headlineSmall)

            OutlinedTextField(
                value = state.title,
                onValueChange = { v -> viewModel.update { it.copy(title = v) } },
                label = { Text(s.titleLabel) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.description,
                onValueChange = { v -> viewModel.update { it.copy(description = v) } },
                label = { Text(s.descriptionOptional) },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.mode == TodoMode.OneTime,
                    onClick = { viewModel.update { it.copy(mode = TodoMode.OneTime) } },
                    label = { Text(s.oneTime) },
                )
                FilterChip(
                    selected = state.mode == TodoMode.Recurring,
                    onClick = { viewModel.update { it.copy(mode = TodoMode.Recurring) } },
                    label = { Text(s.recurring) },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = state.date?.toString() ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(if (state.mode == TodoMode.OneTime) s.dueDate else s.firstOn) },
                    placeholder = { Text(s.pickADate) },
                    trailingIcon = { TextButton(onClick = { dateTarget = DateTarget.Start }) { Text(s.pick) } },
                    modifier = Modifier.weight(1.4f),
                )
                if (state.times.isEmpty()) {
                    OutlinedTextField(
                        value = state.time.formatted(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(s.timeLabel) },
                        trailingIcon = { TextButton(onClick = { timeTarget = TimeTarget.Start }) { Text(s.pick) } },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (state.mode == TodoMode.Recurring) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Frequency.entries.forEach { f ->
                        FilterChip(
                            selected = state.frequency == f,
                            onClick = { viewModel.update { it.copy(frequency = f, times = if (f == Frequency.Daily) it.times else emptyList()) } },
                            label = { Text(s.frequencyName(f)) },
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { viewModel.update { it.copy(interval = (it.interval - 1).coerceAtLeast(1)) } }) { Text("−") }
                    Text(
                        s.every(state.frequency, state.interval),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    OutlinedButton(onClick = { viewModel.update { it.copy(interval = it.interval + 1) } }) { Text("+") }
                }

                if (state.frequency == Frequency.Daily) {
                    Text(s.timesThatDay, style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.times.sorted().forEach { t ->
                            InputChip(
                                selected = false,
                                onClick = { viewModel.update { it.copy(times = it.times - t) } },
                                label = { Text(t.formatted() + "  ✕") },
                            )
                        }
                        OutlinedButton(onClick = { timeTarget = TimeTarget.ExtraTime }) { Text(s.addTime) }
                    }
                    if (!state.timesShareMinute) {
                        Text(
                            s.sameMinuteError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                Text(s.ends, style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.endMode == EndMode.Never,
                        onClick = { viewModel.update { it.copy(endMode = EndMode.Never) } },
                        label = { Text(s.endNever) },
                    )
                    FilterChip(
                        selected = state.endMode == EndMode.AfterCount,
                        onClick = { viewModel.update { it.copy(endMode = EndMode.AfterCount) } },
                        label = { Text(s.endAfterCount) },
                    )
                    FilterChip(
                        selected = state.endMode == EndMode.OnDate,
                        onClick = { viewModel.update { it.copy(endMode = EndMode.OnDate) } },
                        label = { Text(s.endOnDate) },
                    )
                }
                when (state.endMode) {
                    EndMode.AfterCount ->
                        OutlinedTextField(
                            value = state.count?.toString() ?: "",
                            onValueChange = { v -> viewModel.update { it.copy(count = v.toIntOrNull()) } },
                            label = { Text(s.howManyTimes) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    EndMode.OnDate ->
                        OutlinedTextField(
                            value = state.untilDate?.toString() ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(s.lastOccurrenceOn) },
                            placeholder = { Text(s.pickLastDay) },
                            trailingIcon = { TextButton(onClick = { dateTarget = DateTarget.Until }) { Text(s.pick) } },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    EndMode.Never -> {}
                }
            }

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(s.remindMe)
                Switch(checked = state.notify, onCheckedChange = { v -> viewModel.update { it.copy(notify = v) } })
            }

            if (state.error != null || state.errorCode != null) {
                Text(s.errorFor(state.errorCode, state.error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = viewModel::submit, enabled = state.canSubmit, modifier = Modifier.fillMaxWidth()) {
                if (state.isLoading) CircularProgressIndicator(modifier = Modifier.padding(2.dp)) else Text(s.create)
            }
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(s.cancel) }
        }
    }

    val pickingDate = dateTarget
    if (pickingDate != null) {
        val pickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { dateTarget = null },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val picked = LocalDate.fromEpochDays((millis / 86_400_000L).toInt())
                        viewModel.update {
                            when (pickingDate) {
                                DateTarget.Start -> it.copy(date = picked)
                                DateTarget.Until -> it.copy(untilDate = picked)
                            }
                        }
                    }
                    dateTarget = null
                }) { Text(s.ok) }
            },
            dismissButton = { TextButton(onClick = { dateTarget = null }) { Text(s.cancel) } },
        ) { DatePicker(state = pickerState) }
    }

    val target = timeTarget
    if (target != null) {
        val timeState = rememberTimePickerState(initialHour = state.time.hour, initialMinute = state.time.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { timeTarget = null },
            confirmButton = {
                TextButton(onClick = {
                    val picked = LocalTime(timeState.hour, timeState.minute)
                    viewModel.update {
                        when (target) {
                            TimeTarget.Start -> it.copy(time = picked)
                            TimeTarget.ExtraTime -> it.copy(times = (it.times + picked).distinct())
                        }
                    }
                    timeTarget = null
                }) { Text(s.ok) }
            },
            dismissButton = { TextButton(onClick = { timeTarget = null }) { Text(s.cancel) } },
            text = { TimePicker(state = timeState) },
        )
    }
}

private fun LocalTime.formatted(): String =
    hour.toString().padStart(2, '0') + ":" + minute.toString().padStart(2, '0')
