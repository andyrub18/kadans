package app.kadans.ui.pomodoro

import app.kadans.ui.BackButton
import app.kadans.ui.SecondaryButton
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.kadans.api.model.PomodoroPhaseType
import app.kadans.api.model.PomodoroRunStatus
import app.kadans.i18n.LocalStrings
import app.kadans.ui.BigButton
import app.kadans.ui.BigOutlinedButton
import app.kadans.ui.CircleButton
import app.kadans.ui.KadansCard
import app.kadans.ui.LocalDates
import app.kadans.ui.LocalKadansColors
import app.kadans.ui.ProgressRing
import app.kadans.ui.Tag
import app.kadans.ui.shell.KadansIcons
import kotlin.time.Clock
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

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
            SecondaryButton(onClick = viewModel::markDone) { Text(s.pomodoro.markDone) }
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
    val title by viewModel.title.collectAsState()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // A computer: the dial and what one does about it side by side. A phone: one under the other.
        val wide = maxWidth >= SideBySide
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 76.dp, bottom = 24.dp)
                .widthIn(max = if (wide) 900.dp else 520.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (run.status) {
                PomodoroRunStatus.Completed -> {
                    Text(s.pomodoroComplete, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    Text(s.wellDoneMore, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                    val prompt by viewModel.donePrompt.collectAsState()
                    prompt?.let { DoneWith(it, viewModel) }
                    BigButton(s.startAnotherCycle, viewModel::startNew, Modifier.padding(top = 24.dp))
                    TextButton(onClick = onBack) { Text(s.backToTodo) }
                }
                PomodoroRunStatus.Cancelled -> {
                    Text(s.sessionEnded, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    BigButton(s.startNewSession, viewModel::startNew, Modifier.padding(top = 24.dp))
                    TextButton(onClick = onBack) { Text(s.backToTodo) }
                }
                else -> if (run.betweenLaps) BetweenLaps(run, viewModel, onBack) else if (wide) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(56.dp)) {
                        Dial(session, 340.dp)
                        KadansCard(Modifier.weight(1f), padding = PaddingValues(24.dp)) {
                            title?.let { Text(it, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                            Controls(session, viewModel, Alignment.Start)
                        }
                    }
                } else {
                    title?.let {
                        Text(it, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Dial(session, 280.dp, Modifier.padding(top = 16.dp))
                    Controls(session, viewModel, Alignment.CenterHorizontally)
                    // Once, on a phone that can: silence it while a focus phase counts down?
                    app.kadans.ui.settings.QuietOffer(Modifier.padding(top = 16.dp))
                }
            }
        }
        BackButton(onBack, Modifier.align(Alignment.TopStart).padding(start = 20.dp, top = 16.dp))
    }
}

/** A window this wide puts the dial and its buttons side by side. */
private val SideBySide = 760.dp

/**
 * The phase as a ring that fills as its time goes: purple while it is focus, green for a break; in it the phase's
 * name, what is left, and where the session is in its cycle.
 */
@Composable
private fun Dial(session: PomodoroUiState.Session, size: Dp, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val k = LocalKadansColors.current
    val run = session.run
    val phase = run.phases.getOrNull(run.currentPhaseIndex)
    val isBreak = phase?.type == PomodoroPhaseType.Break
    val name = if (isBreak) s.breakWord else s.focus
    val left = PomodoroViewModel.format(session.remaining)
    val where =
        if (run.loop && run.cycleLength > 0)
            "${s.lapWord} ${PomodoroViewModel.lapOf(run.currentPhaseIndex, run.cycleLength)} · ${s.phaseWord} ${PomodoroViewModel.positionInLap(run.currentPhaseIndex, run.cycleLength)} ${s.ofWord} ${run.cycleLength}"
        else
            "${s.phaseWord} ${run.currentPhaseIndex + 1} ${s.ofWord} ${run.phases.size}"
    ProgressRing(
        progress = PhaseStrip.elapsed(phase?.durationMinutes, session.remaining),
        modifier = modifier.size(size).semantics(mergeDescendants = true) { contentDescription = "$name, $left. $where" },
        stroke = size * 0.078f,
        color = if (isBreak) k.done else MaterialTheme.colorScheme.primary,
        track = if (isBreak) k.breakTrack.copy(alpha = 0.4f) else k.ringTrack,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Tag(
                name.uppercase(),
                container = if (isBreak) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
                content = if (isBreak) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                left,
                style = (if (size >= 320.dp) MaterialTheme.typography.displayLarge.copy(fontSize = 84.sp, lineHeight = 88.sp) else MaterialTheme.typography.displayLarge)
                    // Digits of one width, so the time does not shake as it counts; drawn closer, since Baloo's are wide apart.
                    .copy(fontFeatureSettings = "tnum", letterSpacing = (-0.04).em),
                maxLines = 1,
            )
            Text(where, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (run.status == PomodoroRunStatus.Paused) {
                Text(s.paused, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Under (or beside) the dial: the cycle's phases as a strip, when the session ends, and what one can do about it. */
@Composable
private fun Controls(session: PomodoroUiState.Session, viewModel: PomodoroViewModel, align: Alignment.Horizontal) {
    val s = LocalStrings.current
    val run = session.run
    Column(horizontalAlignment = align, modifier = Modifier.fillMaxWidth()) {
        PhaseStripBar(run, Modifier.padding(top = 20.dp).widthIn(max = 420.dp).fillMaxWidth(if (align == Alignment.Start) 1f else 0.82f))
        // A looping session ends by itself at its end time; it can be moved while it runs.
        val end = run.finishAt
        if (run.loop && end != null) {
            var choosingEnd by remember { mutableStateOf(false) }
            val zone = TimeZone.currentSystemDefault()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    SessionEnd.label(end, Clock.System.now(), zone, s.pomodoro, run.endsWithNextOccurrence, LocalDates.current.clock),
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
        // Two tall buttons side by side; in a language whose words are long they wrap whole, one under the other.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp, align),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
        ) {
            if (run.status == PomodoroRunStatus.Active) {
                BigButton(s.pause, viewModel::pause, Modifier.weight(1f), icon = KadansIcons.Pause)
            } else {
                BigButton(s.resume, viewModel::resume, Modifier.weight(1f), icon = KadansIcons.Play)
            }
            BigOutlinedButton(s.nextPhase, viewModel::skipPhase, Modifier.weight(1f), enabled = run.status == PomodoroRunStatus.Active)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, align),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            if (run.loop) {
                TextButton(onClick = viewModel::finish) { Text(s.finishSession) }
                TextButton(onClick = viewModel::end) { Text(s.discard, color = MaterialTheme.colorScheme.error) }
            } else {
                TextButton(onClick = viewModel::end) { Text(s.endSession, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

/** The cycle one is in, phase by phase, each as wide as it is long: gone and current ones full, those ahead pale. */
@Composable
private fun PhaseStripBar(run: app.kadans.api.model.PomodoroRunResponse, modifier: Modifier = Modifier) {
    val k = LocalKadansColors.current
    val strip = PhaseStrip.of(run)
    if (strip.isEmpty()) return
    Row(modifier.clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        strip.forEach { segment ->
            val color = when {
                segment.isBreak && segment.ahead -> k.breakTrack.copy(alpha = 0.55f)
                segment.isBreak -> k.done
                segment.ahead -> k.phaseAhead
                else -> MaterialTheme.colorScheme.primary
            }
            Box(Modifier.weight(segment.weight).height(8.dp).background(color, CircleShape))
        }
    }
}

/**
 * A hands-free cycle ran out and the session waits for the person: another cycle starts the next one now, Finish ends
 * the session (and asks about its occurrence). Left alone, it ends by itself at its end, counting nothing more.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
private fun BetweenLaps(run: app.kadans.api.model.PomodoroRunResponse, viewModel: PomodoroViewModel, onBack: () -> Unit) {
    val s = LocalStrings.current
    Text(
        s.pomodoro.cycleDone(PomodoroViewModel.lapOf(run.currentPhaseIndex, run.cycleLength) - 1),
        style = MaterialTheme.typography.headlineMedium,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
    Text(
        s.pomodoro.anotherCycleQuestion,
        style = MaterialTheme.typography.bodyLarge,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp),
    )
    run.finishAt?.let { end ->
        Text(
            SessionEnd.label(end, Clock.System.now(), TimeZone.currentSystemDefault(), s.pomodoro, run.endsWithNextOccurrence, LocalDates.current.clock),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    BigButton(s.pomodoro.anotherCycle, viewModel::resume, Modifier.padding(top = 24.dp))
    TextButton(onClick = viewModel::finish) { Text(s.finishSession) }
}

/** "When should the session end?": a clock time, its next occurrence (see [SessionEnd.next]). */
@Composable
fun EndTimeDialog(initial: LocalTime, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = !LocalDates.current.twelveHour)
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
