package app.kadans.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import app.kadans.i18n.LanguageController
import app.kadans.i18n.LocalStrings
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun HomeScreen(
    languageController: LanguageController,
    onLoggedOut: () -> Unit,
    onCreateTodo: () -> Unit,
    onOpenTemplates: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTodo: (String) -> Unit,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val s = LocalStrings.current
    val language by languageController.language.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) { viewModel.loggedOut.collect { onLoggedOut() } }
    LaunchedEffect(viewModel) {
        viewModel.liveNotifications.collect { notification ->
            snackbar.showSnackbar(notification.title + " — " + notification.body)
        }
    }
    // Reload whenever this entry comes (back) on screen; data may have changed behind us.
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Kadans", style = MaterialTheme.typography.headlineSmall)
                // FlowRow: five actions won't fit one line on a phone; let them wrap.
                FlowRow(horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { languageController.cycle() }) { Text(language.tag.uppercase()) }
                    TextButton(onClick = onOpenCalendar) { Text(s.calendar) }
                    TextButton(onClick = onOpenTemplates) { Text(s.cycles) }
                    TextButton(onClick = onOpenSettings) { Text("⚙") }
                    TextButton(onClick = viewModel::logout) { Text(s.signOut) }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateTodo) { Text("+", style = MaterialTheme.typography.headlineSmall) }
        },
    ) { padding ->
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
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text(s.next7Days, style = MaterialTheme.typography.titleMedium) }
        if (content.upcoming.isEmpty()) {
            item { Text(s.nothingScheduled, style = MaterialTheme.typography.bodyMedium) }
        }
        items(content.upcoming, key = { it.id ?: it.todoId + it.scheduledAt.toString() }) { occurrence ->
            OccurrenceCard(occurrence, onClick = { onOpenTodo(occurrence.todoId) })
        }

        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { Text(s.allTodos, style = MaterialTheme.typography.titleMedium) }
        if (content.todos.isEmpty()) {
            item { Text(s.noTodosYet, style = MaterialTheme.typography.bodyMedium) }
        }
        items(content.todos, key = { it.id }) { todo -> TodoCard(todo, onClick = { onOpenTodo(todo.id) }) }
    }
}

@Composable
private fun OccurrenceCard(occurrence: TodoOccurrenceResponse, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(occurrence.todoTitle, style = MaterialTheme.typography.titleSmall)
            Text(
                occurrence.scheduledAt.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
