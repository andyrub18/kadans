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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kadans.api.model.OccurrenceStatus
import app.kadans.api.model.TodoOccurrenceResponse
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun TodoDetailScreen(
    todoId: String,
    onOpenPomodoro: () -> Unit,
    onBack: () -> Unit,
    viewModel: TodoDetailViewModel = koinViewModel(key = "todo-$todoId") { parametersOf(todoId) },
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.refresh() }

    when (val current = state) {
        is TodoDetailUiState.Loading ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is TodoDetailUiState.Error ->
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(current.message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onBack) { Text("Back") }
            }
        is TodoDetailUiState.Content -> Detail(current, viewModel, onOpenPomodoro, onBack)
    }
}

@Composable
private fun Detail(
    content: TodoDetailUiState.Content,
    viewModel: TodoDetailViewModel,
    onOpenPomodoro: () -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text(content.todo.status.name, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
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
            Button(onClick = onOpenPomodoro, modifier = Modifier.fillMaxWidth()) {
                Text(if (content.hasActiveRun) "Open focus session" else "Start focus session")
            }
        }

        if (content.actionError != null) {
            item { Text(content.actionError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (content.showHistory) "History" else "Pending occurrences", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = viewModel::toggleHistory) { Text(if (content.showHistory) "Show pending" else "Show history") }
            }
        }
        if (content.occurrences.isEmpty()) {
            item { Text("Nothing here.", style = MaterialTheme.typography.bodyMedium) }
        }
        items(content.occurrences, key = { it.id ?: it.scheduledAt.toString() }) { occurrence ->
            OccurrenceRow(occurrence, viewModel)
        }

        if (content.todo.status.name == "Scheduled" || content.todo.status.name == "Started") {
            item {
                TextButton(onClick = viewModel::cancelTodo, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel this todo", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun OccurrenceRow(occurrence: TodoOccurrenceResponse, viewModel: TodoDetailViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(occurrence.scheduledAt.toString(), style = MaterialTheme.typography.bodyMedium)
                Text(
                    occurrence.status.name + if (occurrence.isRescheduled) " · moved" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (occurrence.status == OccurrenceStatus.Pending && occurrence.id != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { viewModel.completeOccurrence(occurrence.id) }) { Text("Complete") }
                    TextButton(onClick = { viewModel.cancelOccurrence(occurrence.id) }) { Text("Skip") }
                }
            }
        }
    }
}
