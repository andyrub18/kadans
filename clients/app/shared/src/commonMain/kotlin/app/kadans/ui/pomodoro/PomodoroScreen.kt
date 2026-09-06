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

@Composable
fun PomodoroScreen(
    todoId: String,
    loop: Boolean,
    handsFree: Boolean,
    onBack: () -> Unit,
    viewModel: PomodoroViewModel = koinViewModel(key = "pomodoro-$todoId-$loop-$handsFree") { parametersOf(todoId, loop, handsFree) },
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
