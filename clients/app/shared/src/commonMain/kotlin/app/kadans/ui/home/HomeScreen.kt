package app.kadans.ui.home

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kadans.api.model.TodoOccurrenceResponse
import app.kadans.api.model.TodoResponse
import app.kadans.i18n.LocalStrings
import app.kadans.ui.Dates
import app.kadans.ui.FabClearance
import app.kadans.ui.ReadableList
import app.kadans.ui.LocalDates
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.viewmodel.koinViewModel

/** Home's list, inside the destinations' frame (MainShell: the bar, the bell, + for a new todo). */
@Composable
fun HomeScreen(
    onLoggedOut: () -> Unit,
    onOpenTodo: (String) -> Unit,
    onPaywall: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val access by viewModel.access.collectAsState()
    LaunchedEffect(access) { if (access == HomeAccess.Paywall) onPaywall() }
    if (access != HomeAccess.Open) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val state by viewModel.state.collectAsState()
    val s = LocalStrings.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) { viewModel.loggedOut.collect { onLoggedOut() } }
    LaunchedEffect(viewModel) {
        viewModel.liveNotifications.collect { notification ->
            snackbar.showSnackbar(notification.title + " — " + notification.body)
        }
    }
    // Reload whenever this entry comes (back) on screen; data may have changed behind us.
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(modifier = modifier, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        when (val current = state) {
            is HomeUiState.Loading ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            is HomeUiState.Error ->
                Column(
                    Modifier.fillMaxSize().padding(padding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(s.errorFor(current.code, current.message), color = MaterialTheme.colorScheme.error)
                    Button(onClick = viewModel::refresh, modifier = Modifier.padding(top = 12.dp)) { Text(s.retry) }
                }
            is HomeUiState.Content -> HomeContent(current, onOpenTodo, Modifier.padding(padding))
        }
    }
}

@Composable
private fun HomeContent(content: HomeUiState.Content, onOpenTodo: (String) -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val d = LocalDates.current
    val zone = TimeZone.currentSystemDefault()
    val today = Dates.today(zone)
    // Under each day, "Today", "Tomorrow", then its name: the times on the device's clock, as the person lives them.
    val days = content.upcoming.groupBy { it.scheduledAt.toLocalDateTime(zone).date }
    ReadableList(modifier.fillMaxSize(), bottom = FabClearance) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Text(s.next7Days, style = MaterialTheme.typography.titleMedium) }
            if (content.upcoming.isEmpty()) {
                item { Text(s.nothingScheduled, style = MaterialTheme.typography.bodyMedium) }
            }
            days.forEach { (day, occurrences) ->
                item(key = "day $day") {
                    Text(
                        d.day(day, today),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(occurrences, key = { it.id ?: it.todoId + it.scheduledAt.toString() }) { occurrence ->
                    OccurrenceCard(occurrence, d.time(occurrence.scheduledAt, zone), onClick = { onOpenTodo(occurrence.todoId) })
                }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { Text(s.allTodos, style = MaterialTheme.typography.titleMedium) }
            if (content.todos.isEmpty()) {
                item { Text(s.noTodosYet, style = MaterialTheme.typography.bodyMedium) }
            }
            items(content.todos, key = { it.id }) { todo -> TodoCard(todo, onClick = { onOpenTodo(todo.id) }) }
        }
    }
}

@Composable
private fun OccurrenceCard(occurrence: TodoOccurrenceResponse, time: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(occurrence.todoTitle, style = MaterialTheme.typography.titleSmall)
            Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TodoCard(todo: TodoResponse, onClick: () -> Unit) {
    val s = LocalStrings.current
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(todo.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    s.statusName(todo.status),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (todo.description.isNotBlank()) {
                Text(
                    todo.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
