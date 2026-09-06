package app.kadans.ui.pomodoro

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.api.model.CreatePomodoroPhase
import app.kadans.api.model.CreatePomodoroTemplate
import app.kadans.api.model.PomodoroPhaseType
import app.kadans.api.model.PomodoroRunResponse
import app.kadans.api.model.PomodoroRunStatus
import app.kadans.realtime.KadansRealtime
import app.kadans.realtime.RealtimeEvent
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PomodoroUiState {
    data object Loading : PomodoroUiState

    data class Session(val run: PomodoroRunResponse, val remaining: Duration) : PomodoroUiState

    data class Error(val message: String?, val code: String? = null) : PomodoroUiState
}

class PomodoroViewModel(
    private val api: KadansApi,
    private val realtime: KadansRealtime,
    private val todoId: String,
    private val loop: Boolean = true,
    private val handsFree: Boolean = false,
) : ViewModel() {
    private val _state = MutableStateFlow<PomodoroUiState>(PomodoroUiState.Loading)
    val state: StateFlow<PomodoroUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { tick() }
        // Hands-free advances happen server-side; the hub pushes each new phase to us live.
        viewModelScope.launch {
            realtime.events.collect { event ->
                if (event is RealtimeEvent.PomodoroRunChanged && event.run.todoId == todoId) {
                    adopt(event.run)
                }
            }
        }
    }

    /**
     * Called on every entry of the screen. Adopts whatever the server says is live; auto-starts
     * only on the first open (Loading). A finished session stays on screen until the user asks
     * for another cycle — re-entering must never start a session by itself.
     */
    fun refresh() {
        viewModelScope.launch {
            try {
                adopt(api.pomodoro.activeRun(todoId))
            } catch (e: KadansApiException) {
                when {
                    e.errorCode != "10028" -> fail(e)
                    _state.value is PomodoroUiState.Loading -> startInternal()
                    // else: keep showing the finished session
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /** "Start another cycle": a fresh run for the same todo (the old one is completed/cancelled). */
    fun startNew() {
        viewModelScope.launch { startInternal() }
    }

    private suspend fun startInternal() {
        try {
            adopt(api.pomodoro.start(todoId, autoAdvance = handsFree, loop = loop))
        } catch (e: KadansApiException) {
            if (e.errorCode == "10031") {
                // No template attached: give the todo the classic cycle and retry.
                try {
                    val template = api.pomodoro.templates().firstOrNull()
                        ?: api.pomodoro.createTemplate(
                            CreatePomodoroTemplate(
                                "Pomodoro 4×25",
                                // The real pomodoro cycle: four 25-minute focuses with short
                                // breaks, the last break long. Loop repeats it until finished.
                                listOf(
                                    CreatePomodoroPhase(PomodoroPhaseType.Focus, 25),
                                    CreatePomodoroPhase(PomodoroPhaseType.Break, 5),
                                    CreatePomodoroPhase(PomodoroPhaseType.Focus, 25),
                                    CreatePomodoroPhase(PomodoroPhaseType.Break, 5),
                                    CreatePomodoroPhase(PomodoroPhaseType.Focus, 25),
                                    CreatePomodoroPhase(PomodoroPhaseType.Break, 5),
                                    CreatePomodoroPhase(PomodoroPhaseType.Focus, 25),
                                    CreatePomodoroPhase(PomodoroPhaseType.Break, 30),
                                ),
                            )
                        )
                    api.pomodoro.attachTemplate(todoId, template.id)
                    adopt(api.pomodoro.start(todoId, autoAdvance = handsFree, loop = loop))
                } catch (inner: KadansApiException) {
                    fail(inner)
                } catch (inner: Exception) {
                    fail(inner)
                }
            } else fail(e)
        } catch (e: Exception) {
            fail(e)
        }
    }

    fun pause() = mutate { api.pomodoro.pause(it.id) }

    fun resume() = mutate { api.pomodoro.resume(it.id) }

    fun skipPhase() = mutate { api.pomodoro.advance(it.id, it.currentPhaseIndex) }

    fun end() = mutate { api.pomodoro.cancel(it.id) }

    fun finish() = mutate { api.pomodoro.finish(it.id) }

    private fun mutate(action: suspend (PomodoroRunResponse) -> PomodoroRunResponse) {
        val run = (state.value as? PomodoroUiState.Session)?.run ?: return
        viewModelScope.launch {
            try {
                adopt(action(run))
            } catch (e: KadansApiException) {
                fail(e)
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private suspend fun tick() {
        while (true) {
            val current = _state.value
            if (current is PomodoroUiState.Session) {
                _state.value = current.copy(remaining = remainingOf(current.run, Clock.System.now()))
            }
            delay(250)
        }
    }

    private fun adopt(run: PomodoroRunResponse) {
        _state.value = PomodoroUiState.Session(run, remainingOf(run, Clock.System.now()))
    }

    private fun fail(e: Exception) {
        val api = e as? KadansApiException
        _state.value = if (api != null) PomodoroUiState.Error(api.message, api.errorCode) else PomodoroUiState.Error(null, "network")
    }

    internal companion object {
        fun remainingOf(run: PomodoroRunResponse, now: kotlin.time.Instant): Duration = when (run.status) {
            PomodoroRunStatus.Active -> ((run.phaseEndsAt ?: now) - now).coerceAtLeast(Duration.ZERO)
            PomodoroRunStatus.Paused -> (run.pausedRemainingSeconds ?: 0).seconds
            else -> Duration.ZERO
        }

        fun lapOf(phaseIndex: Int, cycleLength: Int): Int =
            if (cycleLength <= 0) 1 else phaseIndex / cycleLength + 1

        fun positionInLap(phaseIndex: Int, cycleLength: Int): Int =
            if (cycleLength <= 0) phaseIndex + 1 else phaseIndex % cycleLength + 1

        fun format(remaining: Duration): String {
            val total = remaining.inWholeSeconds
            return "${total / 60}:" + (total % 60).toString().padStart(2, '0')
        }
    }
}
