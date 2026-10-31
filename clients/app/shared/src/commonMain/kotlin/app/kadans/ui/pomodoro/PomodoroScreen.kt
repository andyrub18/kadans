package app.kadans.ui.pomodoro

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kadans.api.model.PomodoroPhaseType
import app.kadans.api.model.PomodoroRunStatus
import app.kadans.i18n.LocalStrings
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.time.Clock
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun PomodoroScreen(
    todoId: String,
    loop: Boolean,
    handsFree: Boolean,
    finishAtMillis: Long? = null,
    startsOne: Boolean = true,
    onBack: () -> Unit,
    viewModel: PomodoroViewModel = koinViewModel(key = "pomodoro-$todoId-$loop-$handsFree-$finishAtMillis-$startsOne") {
        parametersOf(todoId, loop, handsFree, finishAtMillis, startsOne)
    },
) {
    val state by viewModel.state.collectAsState()
    val s = LocalStrings.current

    // Re-sync with the server every time this screen comes (back) on screen.
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refresh() }

    when (val current = state) {
        is PomodoroUiState.Loading ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is PomodoroUiState.Error ->
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.errorFor(current.code, current.message), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onBack) { Text(s.back) }
            }
        is PomodoroUiState.Session -> Session(current, viewModel, onBack)
    }
}

/** The session was for a todo's occurrence, still pending: done with it? Marked here, it need not be on the todo. */
@Composable
private fun DoneWith(prompt: DonePrompt, viewModel: PomodoroViewModel) {
    val s = LocalStrings.current
    Column(Modifier.padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (prompt.marked) {
            Text("✓ ${s.pomodoro.markedDone}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            return@Column
        }
        Text(s.pomodoro.doneWith(prompt.title), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = viewModel::markDone) { Text(s.pomodoro.markDone) }
            TextButton(onClick = viewModel::notYet) { Text(s.pomodoro.notYet) }
        }
        if (prompt.errorCode != null || prompt.error != null) {
            Text(s.errorFor(prompt.errorCode, prompt.error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Session(session: PomodoroUiState.Session, viewModel: PomodoroViewModel, onBack: () -> Unit) {
    val s = LocalStrings.current
    val run = session.run
    val phase = run.phases.getOrNull(run.currentPhaseIndex)

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (run.status) {
            PomodoroRunStatus.Completed -> {
                Text(s.pomodoroComplete, style = MaterialTheme.typography.headlineMedium)
                Text(s.wellDoneMore, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                val prompt by viewModel.donePrompt.collectAsState()
                prompt?.let { DoneWith(it, viewModel) }
                Button(onClick = viewModel::startNew, modifier = Modifier.padding(top = 24.dp)) { Text(s.startAnotherCycle) }
                TextButton(onClick = onBack) { Text(s.backToTodo) }
            }
            PomodoroRunStatus.Cancelled -> {
                Text(s.sessionEnded, style = MaterialTheme.typography.headlineMedium)
                Button(onClick = viewModel::startNew, modifier = Modifier.padding(top = 24.dp)) { Text(s.startNewSession) }
                TextButton(onClick = onBack) { Text(s.backToTodo) }
            }
            else -> {
                Text(
                    if (phase?.type == PomodoroPhaseType.Break) s.breakWord else s.focus,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    if (run.loop && run.cycleLength > 0)
                        "${s.lapWord} ${PomodoroViewModel.lapOf(run.currentPhaseIndex, run.cycleLength)} · ${s.phaseWord} ${PomodoroViewModel.positionInLap(run.currentPhaseIndex, run.cycleLength)} ${s.ofWord} ${run.cycleLength}"
                    else
                        "${s.phaseWord} ${run.currentPhaseIndex + 1} ${s.ofWord} ${run.phases.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    PomodoroViewModel.format(session.remaining),
                    style = MaterialTheme.typography.displayLarge,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                if (run.status == PomodoroRunStatus.Paused) {
                    Text(s.paused, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // A looping session ends by itself at its end time; it can be moved while it runs.
                val end = run.finishAt
                if (run.loop && end != null) {
                    var choosingEnd by remember { mutableStateOf(false) }
                    val zone = TimeZone.currentSystemDefault()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            SessionEnd.label(end, Clock.System.now(), zone, s.pomodoro, withNext = run.endsWithNextOccurrence),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        TextButton(onClick = { choosingEnd = true }) { Text(s.change) }
                    }
                    if (choosingEnd) {
                        EndTimeDialog(
                            initial = end.toLocalDateTime(zone).time,
                            onPick = { time ->
                                choosingEnd = false
                                viewModel.changeFinishAt(time)
                            },
                            onDismiss = { choosingEnd = false },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 16.dp)) {
                    if (run.status == PomodoroRunStatus.Active) {
                        Button(onClick = viewModel::pause) { Text(s.pause) }
                    } else {
                        Button(onClick = viewModel::resume) { Text(s.resume) }
                    }
                    OutlinedButton(onClick = viewModel::skipPhase, enabled = run.status == PomodoroRunStatus.Active) { Text(s.nextPhase) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                    if (run.loop) {
                        TextButton(onClick = viewModel::finish) { Text(s.finishSession) }
                        TextButton(onClick = viewModel::end) { Text(s.discard, color = MaterialTheme.colorScheme.error) }
                    } else {
                        TextButton(onClick = viewModel::end) { Text(s.endSession, color = MaterialTheme.colorScheme.error) }
                    }
                    TextButton(onClick = onBack) { Text(s.back) }
                }
            }
        }
    }
}

/** "When should the session end?": a clock time, its next occurrence (see [SessionEnd.next]). */
@Composable
fun EndTimeDialog(initial: LocalTime, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.pomodoro.chooseEnd) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TimePicker(state = state)
                Text(s.pomodoro.endHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onPick(LocalTime(state.hour, state.minute)) }) { Text(s.ok) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}
