package app.kadans.ui.todos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kadans.api.model.OccurrenceStatus
import app.kadans.api.model.TodoOccurrenceResponse
import app.kadans.i18n.LocalStrings
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun TodoDetailScreen(
    todoId: String,
    onOpenPomodoro: (loop: Boolean, handsFree: Boolean) -> Unit,
    onEdit: () -> Unit,
    onBack: () -> Unit,
    viewModel: TodoDetailViewModel = koinViewModel(key = "todo-$todoId") { parametersOf(todoId) },
) {
    val state by viewModel.state.collectAsState()
    val s = LocalStrings.current
    LaunchedEffect(Unit) { viewModel.refresh() }

    when (val current = state) {
        is TodoDetailUiState.Loading ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is TodoDetailUiState.Error ->
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.errorFor(current.code, current.message), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onBack) { Text(s.back) }
            }
        is TodoDetailUiState.Content -> Detail(current, viewModel, onOpenPomodoro, onEdit, onBack)
    }
}

@Composable
private fun Detail(
    content: TodoDetailUiState.Content,
    viewModel: TodoDetailViewModel,
    onOpenPomodoro: (loop: Boolean, handsFree: Boolean) -> Unit,
    onEdit: () -> Unit,
    onBack: () -> Unit,
) {
    val s = LocalStrings.current
    var loop by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
    var handsFree by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var pickingTemplate by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var rescheduling by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<TodoOccurrenceResponse?>(null) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← " + s.back) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.statusName(content.todo.status), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = onEdit) { Text(s.editTodo) }
                }
            }
        }
        item { Text(content.todo.title, style = MaterialTheme.typography.headlineSmall) }
        if (content.todo.description.isNotBlank()) {
            item { Text(content.todo.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        content.todo.recurrenceRule?.let { rule ->
            item {
                Text(
                    "${rule.rrule} · ${rule.timeZoneId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.cycleWord + ": " + (content.templates.firstOrNull { it.id == content.todo.pomodoroTemplateId }?.name
                        ?: s.cycleDefault),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { pickingTemplate = true }) { Text(s.change) }
            }
        }
        if (!content.hasActiveRun) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(s.repeatUntilFinish, style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.material3.Switch(checked = loop, onCheckedChange = { loop = it })
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(s.handsFree, style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.material3.Switch(checked = handsFree, onCheckedChange = { handsFree = it })
                }
            }
        }
        item {
            Button(onClick = { onOpenPomodoro(loop, handsFree) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (content.hasActiveRun) s.openFocus else s.startFocus)
            }
        }

        if (content.actionError != null || content.actionErrorCode != null) {
            item { Text(s.errorFor(content.actionErrorCode, content.actionError), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (content.showHistory) s.history else s.pendingOccurrences, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = viewModel::toggleHistory) { Text(if (content.showHistory) s.showPending else s.showHistory) }
            }
        }
        if (content.occurrences.isEmpty()) {
            item { Text(s.nothingHere, style = MaterialTheme.typography.bodyMedium) }
        }
        items(content.occurrences, key = { it.id ?: it.scheduledAt.toString() }) { occurrence ->
            OccurrenceRow(occurrence, viewModel, onMove = { rescheduling = it })
        }

        if (content.todo.status.name == "Scheduled" || content.todo.status.name == "Started") {
            item {
                TextButton(onClick = viewModel::cancelTodo, modifier = Modifier.fillMaxWidth()) {
                    Text(s.cancelThisTodo, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (pickingTemplate) {
        TemplatePickerDialog(
            content = content,
            onPick = { templateId -> viewModel.attachTemplate(templateId); pickingTemplate = false },
            onDismiss = { pickingTemplate = false },
        )
    }

    rescheduling?.let { occurrence ->
        RescheduleDialog(
            occurrence = occurrence,
            onConfirm = { newDate, reason ->
                occurrence.id?.let { viewModel.rescheduleOccurrence(it, newDate, reason) }
                rescheduling = null
            },
            onDismiss = { rescheduling = null },
        )
    }
}

@Composable
private fun OccurrenceRow(
    occurrence: TodoOccurrenceResponse,
    viewModel: TodoDetailViewModel,
    onMove: (TodoOccurrenceResponse) -> Unit,
) {
    val s = LocalStrings.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(occurrence.scheduledAt.toString(), style = MaterialTheme.typography.bodyMedium)
                Text(
                    s.occurrenceStatusName(occurrence.status) + if (occurrence.isRescheduled) " · " + s.moved else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (occurrence.status == OccurrenceStatus.Pending && occurrence.id != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { viewModel.completeOccurrence(occurrence.id) }) { Text(s.complete) }
                    TextButton(onClick = { viewModel.cancelOccurrence(occurrence.id) }) { Text(s.skip) }
                    TextButton(onClick = { onMove(occurrence) }) { Text(s.move) }
                }
            }
        }
    }
}

@Composable
private fun RescheduleDialog(
    occurrence: TodoOccurrenceResponse,
    onConfirm: (kotlin.time.Instant, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    val timeZone = kotlinx.datetime.TimeZone.currentSystemDefault()
    val initial = occurrence.scheduledAt.toLocalDateTime(timeZone)
    var date by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(initial.date) }
    var time by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(initial.time) }
    var reason by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var pickingDate by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var pickingTime by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.rescheduleTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = date.toString(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(s.dueDate) },
                    trailingIcon = { TextButton(onClick = { pickingDate = true }) { Text(s.pick) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = time.hour.toString().padStart(2, '0') + ":" + time.minute.toString().padStart(2, '0'),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(s.timeLabel) },
                    trailingIcon = { TextButton(onClick = { pickingTime = true }) { Text(s.pick) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text(s.reasonOptional) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val instant = kotlinx.datetime.LocalDateTime(date, time).toInstant(timeZone)
                onConfirm(instant, reason.ifBlank { null })
            }) { Text(s.ok) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )

    if (pickingDate) {
        val pickerState = androidx.compose.material3.rememberDatePickerState()
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        date = kotlinx.datetime.LocalDate.fromEpochDays((millis / 86_400_000L).toInt())
                    }
                    pickingDate = false
                }) { Text(s.ok) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(s.cancel) } },
        ) { androidx.compose.material3.DatePicker(state = pickerState) }
    }

    if (pickingTime) {
        val timeState = androidx.compose.material3.rememberTimePickerState(
            initialHour = time.hour, initialMinute = time.minute, is24Hour = true,
        )
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pickingTime = false },
            confirmButton = {
                TextButton(onClick = {
                    time = kotlinx.datetime.LocalTime(timeState.hour, timeState.minute)
                    pickingTime = false
                }) { Text(s.ok) }
            },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text(s.cancel) } },
            text = { androidx.compose.material3.TimePicker(state = timeState) },
        )
    }
}

@Composable
private fun TemplatePickerDialog(
    content: TodoDetailUiState.Content,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.close) } },
        title = { Text(s.cycleDialogTitle) },
        text = {
            Column {
                TextButton(onClick = { onPick(null) }) { Text(s.cycleNone) }
                content.templates.forEach { template ->
                    TextButton(onClick = { onPick(template.id) }) { Text(template.name) }
                }
            }
        },
    )
}
