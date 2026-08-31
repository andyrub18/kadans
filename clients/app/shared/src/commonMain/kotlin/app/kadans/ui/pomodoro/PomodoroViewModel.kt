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

    data class Error(val message: String) : PomodoroUiState
}

class PomodoroViewModel(private val api: KadansApi, private val todoId: String) : ViewModel() {
    private val _state = MutableStateFlow<PomodoroUiState>(PomodoroUiState.Loading)
    val state: StateFlow<PomodoroUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            ensureRun()
            tick()
        }
    }

    /** Resume the todo's active run, or start one (attaching a Classic template if none is set). */
    private suspend fun ensureRun() {
        try {
            adopt(api.pomodoro.activeRun(todoId))
            return
        } catch (e: KadansApiException) {
            if (e.errorCode != "10028") { fail(e); return }
        } catch (e: Exception) { fail(e); return }

        try {
            adopt(api.pomodoro.start(todoId))
        } catch (e: KadansApiException) {
            if (e.errorCode == "10031") {
                try {
                    val template = api.pomodoro.templates().firstOrNull()
                        ?: api.pomodoro.createTemplate(
                            CreatePomodoroTemplate(
                                "Classic",
                                listOf(
                                    CreatePomodoroPhase(PomodoroPhaseType.Focus, 25),
                                    CreatePomodoroPhase(PomodoroPhaseType.Break, 5),
                                    CreatePomodoroPhase(PomodoroPhaseType.Focus, 25),
                                ),
                            )
                        )
                    api.pomodoro.attachTemplate(todoId, template.id)
                    adopt(api.pomodoro.start(todoId))
                } catch (inner: Exception) { fail(inner) }
            } else fail(e)
        } catch (e: Exception) { fail(e) }
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
        _state.value = PomodoroUiState.Error((e as? KadansApiException)?.message ?: "Could not reach the server.")
    }

    fun pause() = mutate { api.pomodoro.pause(it.id) }

    fun resume() = mutate { api.pomodoro.resume(it.id) }

    fun skipPhase() = mutate { api.pomodoro.advance(it.id, it.currentPhaseIndex) }

    fun end() = mutate { api.pomodoro.cancel(it.id) }

    private fun mutate(action: suspend (PomodoroRunResponse) -> PomodoroRunResponse) {
        val run = (state.value as? PomodoroUiState.Session)?.run ?: return
        viewModelScope.launch {
            try { adopt(action(run)) } catch (e: KadansApiException) { fail(e) } catch (e: Exception) { fail(e) }
        }
    }

    internal companion object {
        /**
         * The server is the clock's owner: active runs count down to phaseEndsAt, paused runs
         * hold pausedRemainingSeconds, finished runs are at zero.
         */
        fun remainingOf(run: PomodoroRunResponse, now: kotlin.time.Instant): Duration = when (run.status) {
            PomodoroRunStatus.Active -> ((run.phaseEndsAt ?: now) - now).coerceAtLeast(Duration.ZERO)
            PomodoroRunStatus.Paused -> (run.pausedRemainingSeconds ?: 0).seconds
            else -> Duration.ZERO
        }

        fun format(remaining: Duration): String {
            val total = remaining.inWholeSeconds
            val minutes = total / 60
            val seconds = total % 60
            return "$minutes:" + seconds.toString().padStart(2, '0')
        }
    }
}
