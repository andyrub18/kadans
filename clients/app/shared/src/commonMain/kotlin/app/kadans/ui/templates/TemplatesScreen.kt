package app.kadans.ui.templates

import app.kadans.ui.SecondaryButton
import app.kadans.ui.SectionHeader
import app.kadans.ui.shell.KadansIcons
import app.kadans.ui.Segmented
import app.kadans.ui.FieldLabel
import app.kadans.ui.FieldGroup
import app.kadans.ui.FormActions
import app.kadans.ui.FormPage
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import app.kadans.ui.KadansField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import app.kadans.api.model.CreatePomodoroPhase
import app.kadans.api.model.PomodoroPhaseType
import app.kadans.api.model.PomodoroTemplateResponse
import app.kadans.i18n.LocalStrings
import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** The Pomodoro cycles, the other side of the Focus destination (FocusScreen); a cycle opens in place to edit. */
@Composable
fun TemplatesPane(
    modifier: Modifier = Modifier,
    viewModel: TemplatesViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val editor by viewModel.editor.collectAsState()

    LaunchedEffect(Unit) { viewModel.refresh() }

    val s = LocalStrings.current
    Box(modifier) {
        val editing = editor
        when {
            editing != null -> TemplateEditor(editing, viewModel)
            else -> when (val current = state) {
                is TemplatesUiState.Loading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is TemplatesUiState.Error ->
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s.errorFor(current.code, current.message), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = viewModel::refresh) { Text(s.retry) }
                    }
                is TemplatesUiState.Content -> TemplateList(current.templates, viewModel)
            }
        }
    }
}

@Composable
private fun TemplateList(
    templates: List<PomodoroTemplateResponse>,
    viewModel: TemplatesViewModel,
) {
    val s = LocalStrings.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionHeader(s.pomodoroCycles, action = s.newCycle, onAction = viewModel::openNew)
        }
        if (templates.isEmpty()) {
            item { Text(s.noCyclesYet, style = MaterialTheme.typography.bodyMedium) }
        }
        items(templates, key = { it.id }) { template ->
            Card(onClick = { viewModel.openEdit(template) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(template.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        template.phases.sortedBy { it.order }.joinToString(" · ") {
                            "${it.durationMinutes}m ${if (it.type == PomodoroPhaseType.Focus) s.focus.lowercase() else s.breakWord.lowercase()}"
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
    val s = LocalStrings.current
    FormPage(if (editing.id == null) s.newCycle else s.editCycle, onClose = viewModel::closeEditor) { wide ->
        KadansField(
            value = editing.name,
            onValueChange = { v -> viewModel.updateEditor { it.copy(name = v) } },
            label = { Text(s.nameLabel) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        ClassicCycleBuilder { phases -> viewModel.updateEditor { it.copy(phases = phases) } }

        FieldGroup(s.phasesInOrder) {
            editing.phases.forEachIndexed { index, phase ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Segmented(
                        options = listOf(PomodoroPhaseType.Focus, PomodoroPhaseType.Break),
                        selected = phase.type,
                        name = { if (it == PomodoroPhaseType.Focus) s.focus else s.breakWord },
                        onSelect = { type ->
                            viewModel.updateEditor {
                                it.copy(phases = it.phases.mapIndexed { i, p -> if (i == index) p.copy(type = type) else p })
                            }
                        },
                        modifier = Modifier.weight(1.5f),
                    )
                    KadansField(
                        value = phase.durationMinutes.toString(),
                        onValueChange = { v ->
                            viewModel.updateEditor {
                                it.copy(phases = it.phases.mapIndexed { i, p ->
                                    if (i == index) p.copy(durationMinutes = v.filter(Char::isDigit).take(3).toIntOrNull() ?: 0) else p
                                })
                            }
                        },
                        label = { Text(s.minutes) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        viewModel.updateEditor { it.copy(phases = it.phases.filterIndexed { i, _ -> i != index }) }
                    }) { Icon(KadansIcons.Close, contentDescription = s.deleteWord, modifier = Modifier.size(20.dp)) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                viewModel.updateEditor { it.copy(phases = it.phases + CreatePomodoroPhase(PomodoroPhaseType.Focus, 25)) }
            }) { Text(s.addFocus) }
            TextButton(onClick = {
                viewModel.updateEditor { it.copy(phases = it.phases + CreatePomodoroPhase(PomodoroPhaseType.Break, 5)) }
            }) { Text(s.addBreak) }
        }
        Text(
            s.pomodoro.cycleLimits,
            style = MaterialTheme.typography.bodySmall,
            color = if (editing.withinLimits) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )

        if (editing.error != null || editing.errorCode != null) {
            Text(s.errorFor(editing.errorCode, editing.error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        FormActions(
            wide = wide,
            confirm = s.save,
            onConfirm = viewModel::save,
            onCancel = viewModel::closeEditor,
            enabled = editing.canSave,
            busy = editing.isSaving,
        )
        if (editing.id != null) {
            TextButton(onClick = { viewModel.delete(editing.id) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(s.deleteCycle, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** "Focus 15, short break 5, 4 rounds, long break 30" → the eight phases, in place of the list below. */
@Composable
private fun ClassicCycleBuilder(onFill: (List<CreatePomodoroPhase>) -> Unit) {
    val p = LocalStrings.current.pomodoro
    var focus by remember { mutableStateOf("25") }
    var shortBreak by remember { mutableStateOf("5") }
    var rounds by remember { mutableStateOf("4") }
    var longBreak by remember { mutableStateOf("30") }
    val values = listOf(focus, shortBreak, rounds, longBreak).map { it.toIntOrNull() }
    val (f, sb, r, lb) = values
    val valid = f in 1..TemplateEditorState.MAX_MINUTES && sb in 1..TemplateEditorState.MAX_MINUTES &&
        r in 1..TemplateEditorState.MAX_PHASES / 2 && lb != null && lb in 0..TemplateEditorState.MAX_MINUTES

    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldLabel(p.builderTitle)
            @Composable
            fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) = KadansField(
                value = value,
                onValueChange = { onChange(it.filter(Char::isDigit).take(3)) },
                label = { Text(label) },
                singleLine = true,
                modifier = modifier,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(p.builderFocus, focus, { focus = it }, Modifier.weight(1f))
                Field(p.builderShortBreak, shortBreak, { shortBreak = it }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(p.builderRounds, rounds, { rounds = it }, Modifier.weight(1f))
                Field(p.builderLongBreak, longBreak, { longBreak = it }, Modifier.weight(1f))
            }
            SecondaryButton(
                onClick = { onFill(TemplateEditorState.classicCycle(f!!, sb!!, r!!, lb!!)) },
                enabled = valid,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(p.builderFill) }
        }
    }
}
