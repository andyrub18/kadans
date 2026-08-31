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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kadans.api.model.TodoOccurrenceResponse
import app.kadans.api.model.TodoResponse
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun HomeScreen(
    onLoggedOut: () -> Unit,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(viewModel) { viewModel.loggedOut.collect { onLoggedOut() } }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Kadans", style = MaterialTheme.typography.headlineSmall)
                Row {
                    TextButton(onClick = viewModel::refresh) { Text("Refresh") }
                    TextButton(onClick = viewModel::logout) { Text("Sign out") }
                }
            }
        }
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
                    Text(current.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = viewModel::refresh, modifier = Modifier.padding(top = 12.dp)) { Text("Retry") }
                }
            is HomeUiState.Content -> HomeContent(current, Modifier.padding(padding))
        }
    }
}

@Composable
private fun HomeContent(content: HomeUiState.Content, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("Next 7 days", style = MaterialTheme.typography.titleMedium) }
        if (content.upcoming.isEmpty()) {
            item { Text("Nothing scheduled.", style = MaterialTheme.typography.bodyMedium) }
        }
        items(content.upcoming, key = { it.id ?: it.todoId + it.scheduledAt.toString() }) { occurrence ->
            OccurrenceCard(occurrence)
        }

        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { Text("All todos", style = MaterialTheme.typography.titleMedium) }
        if (content.todos.isEmpty()) {
            item { Text("No todos yet.", style = MaterialTheme.typography.bodyMedium) }
        }
        items(content.todos, key = { it.id }) { todo -> TodoCard(todo) }
    }
}

@Composable
private fun OccurrenceCard(occurrence: TodoOccurrenceResponse) {
    Card(modifier = Modifier.fillMaxWidth()) {
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
private fun TodoCard(todo: TodoResponse) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(todo.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    todo.status.name,
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
