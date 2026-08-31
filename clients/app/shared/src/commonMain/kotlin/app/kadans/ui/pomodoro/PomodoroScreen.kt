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
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun PomodoroScreen(
    todoId: String,
    onBack: () -> Unit,
    viewModel: PomodoroViewModel = koinViewModel(key = "pomodoro-$todoId") { parametersOf(todoId) },
) {
    val state by viewModel.state.collectAsState()

    when (val current = state) {
        is PomodoroUiState.Loading ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is PomodoroUiState.Error ->
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(current.message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onBack) { Text("Back") }
            }
        is PomodoroUiState.Session -> Session(current, viewModel, onBack)
    }
}

@Composable
private fun Session(session: PomodoroUiState.Session, viewModel: PomodoroViewModel, onBack: () -> Unit) {
    val run = session.run
    val phase = run.phases.getOrNull(run.currentPhaseIndex)

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (run.status) {
            PomodoroRunStatus.Completed -> {
                Text("Pomodoro complete", style = MaterialTheme.typography.headlineMedium)
                Text("Well done.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                Button(onClick = onBack, modifier = Modifier.padding(top = 24.dp)) { Text("Back to todo") }
            }
            PomodoroRunStatus.Cancelled -> {
                Text("Session ended", style = MaterialTheme.typography.headlineMedium)
                Button(onClick = onBack, modifier = Modifier.padding(top = 24.dp)) { Text("Back to todo") }
            }
            else -> {
                Text(
                    if (phase?.type == PomodoroPhaseType.Break) "Break" else "Focus",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Phase ${run.currentPhaseIndex + 1} of ${run.phases.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    PomodoroViewModel.format(session.remaining),
                    style = MaterialTheme.typography.displayLarge,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                if (run.status == PomodoroRunStatus.Paused) {
                    Text("Paused", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 16.dp)) {
                    if (run.status == PomodoroRunStatus.Active) {
                        Button(onClick = viewModel::pause) { Text("Pause") }
                    } else {
                        Button(onClick = viewModel::resume) { Text("Resume") }
                    }
                    OutlinedButton(onClick = viewModel::skipPhase, enabled = run.status == PomodoroRunStatus.Active) { Text("Skip phase") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                    TextButton(onClick = viewModel::end) { Text("End session", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = onBack) { Text("Back") }
                }
            }
        }
    }
}
