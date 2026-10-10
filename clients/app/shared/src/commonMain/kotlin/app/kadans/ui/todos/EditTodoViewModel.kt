package app.kadans.ui.todos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.api.model.TodoOccurrenceResponse
import app.kadans.api.model.TodoResponse
import app.kadans.api.model.UpdateTodo
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn

data class EditTodoUiState(
    val todo: TodoResponse? = null,
    val title: String = "",
    val description: String = "",
    val notify: Boolean = false,
    val notifyBefore: Int? = 15,
    /** A recurring todo's rule, as the create form would show it (null for a one-time todo). */
    val rule: CreateTodoUiState? = null,
    /** Every part of the stored rule could be shown; false for a rule made through the API with others. */
    val ruleWorded: Boolean = true,
    /** The rule is sent only once the person changed it: otherwise it stays exactly as stored. */
    val ruleChanged: Boolean = false,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
    val errorCode: String? = null,
) {
    val canSave: Boolean get() = todo != null && title.isNotBlank() && !isSaving && (!ruleChanged || rule?.ruleValid == true)
}

class EditTodoViewModel(private val api: KadansApi, private val todoId: String) : ViewModel() {
    private val _state = MutableStateFlow(EditTodoUiState())
    val state: StateFlow<EditTodoUiState> = _state.asStateFlow()

    private val _saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saved: SharedFlow<Unit> = _saved.asSharedFlow()

    private val timeZone = TimeZone.currentSystemDefault()

    init {
        viewModelScope.launch {
            try {
                val todo = api.todos.get(todoId)
                val stored = todo.recurrenceRule?.takeUnless { it.isOneTime }
                val draft = stored?.let {
                    val now = Clock.System.now()
                    RuleDraft.of(it, firstOn(api.todos.occurrences(todoId, pageSize = 20), now, timeZone))
                }
                _state.value = EditTodoUiState(
                    todo = todo,
                    title = todo.title,
                    description = todo.description,
                    notify = todo.notificationEnabled,
                    notifyBefore = todo.notifyBeforeInMinutes,
                    rule = draft?.state,
                    ruleWorded = draft?.worded ?: true,
                    isLoading = false,
                )
            } catch (e: KadansApiException) {
                _state.value = EditTodoUiState(isLoading = false, error = e.message, errorCode = e.errorCode)
            } catch (e: Exception) {
                _state.value = EditTodoUiState(isLoading = false, errorCode = "network")
            }
        }
    }

    fun update(transform: (EditTodoUiState) -> EditTodoUiState) {
        _state.value = transform(_state.value)
    }

    /** A change to the rule: from now on it is sent with the rest. */
    fun updateRule(transform: (CreateTodoUiState) -> CreateTodoUiState) {
        val current = _state.value
        val rule = current.rule ?: return
        _state.value = current.copy(rule = transform(rule), ruleChanged = true, error = null, errorCode = null)
    }

    fun save() {
        val current = _state.value
        val todo = current.todo ?: return
        val now = Clock.System.now()
        val rule = current.rule?.takeIf { current.ruleChanged }
        if (rule != null && !CreateTodoViewModel.ruleReady(rule, timeZone, now)) return
        _state.value = current.copy(isSaving = true, error = null, errorCode = null)
        viewModelScope.launch {
            try {
                api.todos.update(
                    todoId,
                    UpdateTodo(
                        title = current.title.trim(),
                        description = current.description.trim(),
                        notificationEnabled = current.notify,
                        // Not editable here; resent so the update doesn't clear it.
                        pomodoroTemplateId = todo.pomodoroTemplateId,
                        // A changed rule replaces the stored one from its first moment; the server keeps every occurrence
                        // someone moved, completed or annotated, and regenerates the rest.
                        recurrenceRule = rule?.let { CreateTodoViewModel.buildRecurring(it, timeZone, now).recurrenceRule },
                        notifyBeforeInMinutes = current.notifyBefore,
                    ),
                )
                // Clear the flag before leaving: even a reused ViewModel must never keep the button stuck.
                _state.value = _state.value.copy(isSaving = false)
                _saved.emit(Unit)
            } catch (e: KadansApiException) {
                val details = e.problem?.errors?.mapNotNull { it.message }?.joinToString("\n")
                _state.value = _state.value.copy(
                    isSaving = false,
                    error = details?.ifBlank { null } ?: e.message,
                    errorCode = if (details.isNullOrBlank()) e.errorCode else null,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(isSaving = false, errorCode = "network")
            }
        }
    }

    internal companion object {
        /**
         * The day an edited rule starts from: that of the next occurrence nobody moved (pending, still ahead), else today.
         * A moved one keeps its own time, which says nothing of the rule's.
         */
        fun firstOn(pending: List<TodoOccurrenceResponse>, now: Instant, timeZone: TimeZone): LocalDate =
            pending.filter { !it.isRescheduled && it.originalScheduledAt > now }
                .minOfOrNull { it.originalScheduledAt }
                ?.toLocalDateTime(timeZone)?.date
                ?: Clock.System.todayIn(timeZone)
    }
}
