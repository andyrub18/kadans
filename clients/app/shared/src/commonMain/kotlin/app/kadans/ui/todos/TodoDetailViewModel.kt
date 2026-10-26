package app.kadans.ui.todos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.api.model.RescheduleOccurrence
import app.kadans.api.model.TodoOccurrenceResponse
import app.kadans.api.model.PomodoroRunResponse
import app.kadans.api.model.PomodoroTemplateResponse
import app.kadans.api.model.TodoResponse
import app.kadans.reminders.ReminderActions
import app.kadans.sync.DataArea
import app.kadans.sync.DataChanges
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface TodoDetailUiState {
    data object Loading : TodoDetailUiState

    data class Content(
        val todo: TodoResponse,
        val occurrences: List<TodoOccurrenceResponse>,
        val showHistory: Boolean,
        val hasActiveRun: Boolean,
        val templates: List<PomodoroTemplateResponse> = emptyList(),
        /** The latest focus sessions on this todo, newest first. */
        val runs: List<PomodoroRunResponse> = emptyList(),
        val actionError: String? = null,
        val actionErrorCode: String? = null,
    ) : TodoDetailUiState

    data class Error(val message: String?, val code: String? = null) : TodoDetailUiState
}

/** Why a note was not saved ([KadansApiException]'s code, or "network"): the dialog says so and keeps the text. */
data class NoteFailure(val code: String?, val message: String?)

class TodoDetailViewModel(
    private val api: KadansApi,
    private val todoId: String,
    private val dataChanges: DataChanges,
) : ViewModel() {
    private val _state = MutableStateFlow<TodoDetailUiState>(TodoDetailUiState.Loading)
    val state: StateFlow<TodoDetailUiState> = _state.asStateFlow()

    private var showHistory = false

    private companion object {
        const val RECENT_RUNS = 5
    }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    fun toggleHistory() {
        showHistory = !showHistory
        refresh()
    }

    // Done or skipped already (a notification's button, another device): the screen shows it so, without an error.
    fun completeOccurrence(occurrenceId: String) = act(alreadySo = ReminderActions.ALREADY_DONE) { api.todos.completeOccurrence(occurrenceId) }

    fun cancelOccurrence(occurrenceId: String) = act(alreadySo = ReminderActions.ALREADY_CANCELLED) { api.todos.cancelOccurrence(occurrenceId) }

    fun rescheduleOccurrence(occurrenceId: String, newDate: Instant, reason: String?) =
        act { api.todos.rescheduleOccurrence(occurrenceId, RescheduleOccurrence(newDate, reason?.ifBlank { null })) }

    fun cancelTodo() = act { api.todos.cancel(todoId) }

    /**
     * The person's note on an occurrence ("how it went"), whatever its status; null or blank removes it. Null once the
     * server has it (the screen shows it then), else why not: the dialog stays open, its text kept.
     */
    suspend fun saveNote(occurrenceId: String, note: String?): NoteFailure? = try {
        api.todos.setOccurrenceNote(occurrenceId, note)
        load()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: KadansApiException) {
        NoteFailure(e.errorCode, e.message)
    } catch (_: Exception) {
        NoteFailure("network", null)
    }

    private val _deleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The todo is gone for good (here or on another device): the screen closes. */
    val deleted: SharedFlow<Unit> = _deleted.asSharedFlow()

    init {
        // Changed elsewhere (another device, a notification's button), or back in front: shown as it is now.
        viewModelScope.launch { dataChanges.of(DataArea.Todos).collect { load(quiet = true) } }
    }

    fun deleteTodo() {
        viewModelScope.launch {
            try {
                api.todos.delete(todoId)
                _deleted.emit(Unit)
            } catch (e: KadansApiException) {
                val current = _state.value
                if (current is TodoDetailUiState.Content)
                    _state.value = current.copy(actionError = e.message, actionErrorCode = e.errorCode)
            } catch (_: Exception) {
                val current = _state.value
                if (current is TodoDetailUiState.Content) _state.value = current.copy(actionError = null, actionErrorCode = "network")
            }
        }
    }

    fun attachTemplate(templateId: String?) = act { api.pomodoro.attachTemplate(todoId, templateId) }

    /**
     * Asks the server, then shows what it holds now. Refused, the screen loads again all the same (it may have changed
     * elsewhere meanwhile) and says why, unless the refusal only says it is already as asked ([alreadySo]).
     */
    private fun act(alreadySo: String? = null, action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: KadansApiException) {
                load()
                val current = _state.value
                if (e.errorCode != alreadySo && current is TodoDetailUiState.Content)
                    _state.value = current.copy(actionError = e.message, actionErrorCode = e.errorCode)
            } catch (e: Exception) {
                load()
            }
        }
    }

    /**
     * [quiet]: nobody asked. A todo deleted elsewhere closes the screen; another failure (offline) keeps what is shown.
     */
    private suspend fun load(quiet: Boolean = false) {
        try {
            val todo = api.todos.get(todoId)
            val occurrences =
                if (showHistory) api.todos.history(todoId, pageSize = 50)
                else api.todos.occurrences(todoId, pageSize = 50)
            val hasActiveRun = runCatching { api.pomodoro.activeRun(todoId) }.isSuccess
            val templates = runCatching { api.pomodoro.templates() }.getOrElse { emptyList() }
            val runs = runCatching { api.pomodoro.runs(todoId, page = 1, pageSize = RECENT_RUNS) }.getOrElse { emptyList() }
            _state.value = TodoDetailUiState.Content(todo, occurrences, showHistory, hasActiveRun, templates, runs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: KadansApiException) {
            when {
                quiet && e.httpStatus == 404 -> _deleted.emit(Unit)
                quiet && _state.value is TodoDetailUiState.Content -> {}
                else -> _state.value = TodoDetailUiState.Error(e.message, e.errorCode)
            }
        } catch (e: Exception) {
            if (!(quiet && _state.value is TodoDetailUiState.Content)) _state.value = TodoDetailUiState.Error(null, "network")
        }
    }
}
