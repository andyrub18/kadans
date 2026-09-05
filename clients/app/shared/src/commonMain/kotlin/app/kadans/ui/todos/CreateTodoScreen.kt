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
import org.koin.compose.viewmodel.koinViewModel

private enum class TimeTarget { Start, ExtraTime }

@Composable
fun CreateTodoScreen(
    onCreated: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: CreateTodoViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var showDatePicker by remember { mutableStateOf(false) }
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
            Text("New todo", style = MaterialTheme.typography.headlineSmall)

            OutlinedTextField(
                value = state.title,
                onValueChange = { v -> viewModel.update { it.copy(title = v) } },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.description,
                onValueChange = { v -> viewModel.update { it.copy(description = v) } },
                label = { Text("Description (optional)") },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.mode == TodoMode.OneTime,
                    onClick = { viewModel.update { it.copy(mode = TodoMode.OneTime) } },
                    label = { Text("One-time") },
                )
                FilterChip(
                    selected = state.mode == TodoMode.Recurring,
                    onClick = { viewModel.update { it.copy(mode = TodoMode.Recurring) } },
                    label = { Text("Recurring") },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = state.date?.toString() ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(if (state.mode == TodoMode.OneTime) "Due date" else "First on") },
                    placeholder = { Text("Pick a date") },
                    trailingIcon = { TextButton(onClick = { showDatePicker = true }) { Text("Pick") } },
                    modifier = Modifier.weight(1.4f),
                )
                if (state.times.isEmpty()) {
                    OutlinedTextField(
                        value = state.time.formatted(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Time") },
                        trailingIcon = { TextButton(onClick = { timeTarget = TimeTarget.Start }) { Text("Pick") } },
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
                            label = { Text(f.name) },
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { viewModel.update { it.copy(interval = (it.interval - 1).coerceAtLeast(1)) } }) { Text("−") }
                    Text(
                        CreateTodoViewModel.everyLabel(state.frequency, state.interval),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    OutlinedButton(onClick = { viewModel.update { it.copy(interval = it.interval + 1) } }) { Text("+") }
                }

                if (state.frequency == Frequency.Daily) {
                    Text("Times that day (for “3 times a day”)", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.times.sorted().forEach { t ->
                            InputChip(
                                selected = false,
                                onClick = { viewModel.update { it.copy(times = it.times - t) } },
                                label = { Text(t.formatted() + "  ✕") },
                            )
                        }
                        OutlinedButton(onClick = { timeTarget = TimeTarget.ExtraTime }) { Text("+ Add time") }
                    }
                    if (!state.timesShareMinute) {
                        Text(
                            "All daily times must share the same minutes — e.g. 8:00, 14:00, 20:00 (a recurrence-rule constraint).",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                OutlinedTextField(
                    value = state.count?.toString() ?: "",
                    onValueChange = { v -> viewModel.update { it.copy(count = v.toIntOrNull()) } },
                    label = { Text("How many times in total (blank = forever)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Remind me before it starts")
                Switch(checked = state.notify, onCheckedChange = { v -> viewModel.update { it.copy(notify = v) } })
            }

            if (state.error != null) {
                Text(state.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = viewModel::submit, enabled = state.canSubmit, modifier = Modifier.fillMaxWidth()) {
                if (state.isLoading) CircularProgressIndicator(modifier = Modifier.padding(2.dp)) else Text("Create")
            }
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        viewModel.update { it.copy(date = LocalDate.fromEpochDays((millis / 86_400_000L).toInt())) }
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
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
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { timeTarget = null }) { Text("Cancel") } },
            text = { TimePicker(state = timeState) },
        )
    }
}

private fun LocalTime.formatted(): String =
    hour.toString().padStart(2, '0') + ":" + minute.toString().padStart(2, '0')
