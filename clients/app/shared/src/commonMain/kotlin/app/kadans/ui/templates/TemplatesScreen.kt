package app.kadans.ui.templates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kadans.api.model.CreatePomodoroPhase
import app.kadans.api.model.PomodoroPhaseType
import app.kadans.api.model.PomodoroTemplateResponse
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun TemplatesScreen(
    onBack: () -> Unit,
    viewModel: TemplatesViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val editor by viewModel.editor.collectAsState()

    LaunchedEffect(Unit) { viewModel.refresh() }

    val editing = editor
    if (editing != null) {
        TemplateEditor(editing, viewModel)
        return
    }

    when (val current = state) {
        is TemplatesUiState.Loading ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is TemplatesUiState.Error ->
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(current.message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::refresh) { Text("Retry") }
                TextButton(onClick = onBack) { Text("Back") }
            }
        is TemplatesUiState.Content -> TemplateList(current.templates, viewModel, onBack)
    }
}

@Composable
private fun TemplateList(
    templates: List<PomodoroTemplateResponse>,
    viewModel: TemplatesViewModel,
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
                Text("Pomodoro cycles", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = viewModel::openNew) { Text("New") }
            }
        }
        if (templates.isEmpty()) {
            item { Text("No cycles yet — create one, or start a session and get the default.", style = MaterialTheme.typography.bodyMedium) }
        }
        items(templates, key = { it.id }) { template ->
            Card(onClick = { viewModel.openEdit(template) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(template.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        template.phases.sortedBy { it.order }.joinToString(" · ") {
                            "${it.durationMinutes}m ${if (it.type == PomodoroPhaseType.Focus) "focus" else "break"}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TemplateEditor(editing: TemplateEditorState, viewModel: TemplatesViewModel) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(modifier = Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (editing.id == null) "New cycle" else "Edit cycle", style = MaterialTheme.typography.headlineSmall)

            OutlinedTextField(
                value = editing.name,
                onValueChange = { v -> viewModel.updateEditor { it.copy(name = v) } },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Phases, in order", style = MaterialTheme.typography.labelLarge)
            editing.phases.forEachIndexed { index, phase ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    FilterChip(
                        selected = phase.type == PomodoroPhaseType.Focus,
                        onClick = {
                            viewModel.updateEditor {
                                it.copy(phases = it.phases.mapIndexed { i, p ->
                                    if (i == index) p.copy(type = if (p.type == PomodoroPhaseType.Focus) PomodoroPhaseType.Break else PomodoroPhaseType.Focus) else p
                                })
                            }
                        },
                        label = { Text(if (phase.type == PomodoroPhaseType.Focus) "Focus" else "Break") },
                    )
                    OutlinedTextField(
                        value = phase.durationMinutes.toString(),
                        onValueChange = { v ->
                            viewModel.updateEditor {
                                it.copy(phases = it.phases.mapIndexed { i, p ->
                                    if (i == index) p.copy(durationMinutes = v.toIntOrNull() ?: 0) else p
                                })
                            }
                        },
                        label = { Text("Minutes") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        viewModel.updateEditor { it.copy(phases = it.phases.filterIndexed { i, _ -> i != index }) }
                    }) { Text("✕") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    viewModel.updateEditor { it.copy(phases = it.phases + CreatePomodoroPhase(PomodoroPhaseType.Focus, 25)) }
                }) { Text("+ Focus") }
                OutlinedButton(onClick = {
                    viewModel.updateEditor { it.copy(phases = it.phases + CreatePomodoroPhase(PomodoroPhaseType.Break, 5)) }
                }) { Text("+ Break") }
            }

            if (editing.error != null) {
                Text(editing.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = viewModel::save, enabled = editing.canSave, modifier = Modifier.fillMaxWidth()) {
                if (editing.isSaving) CircularProgressIndicator(modifier = Modifier.padding(2.dp)) else Text("Save")
            }
            if (editing.id != null) {
                TextButton(onClick = { viewModel.delete(editing.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete this cycle", color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(onClick = viewModel::closeEditor, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}
