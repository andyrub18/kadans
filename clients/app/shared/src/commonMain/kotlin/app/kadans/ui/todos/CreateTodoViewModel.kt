package app.kadans.ui.todos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.api.model.CreateOneTimeTodo
import app.kadans.api.model.CreateRecurrenceRule
import app.kadans.api.model.CreateRecurringTodo
import app.kadans.api.model.Frequency
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TodoMode { OneTime, Recurring }

data class CreateTodoUiState(
    val title: String = "",
    val description: String = "",
    val notify: Boolean = true,
    val mode: TodoMode = TodoMode.OneTime,
    val date: LocalDate? = null,
    val time: LocalTime = LocalTime(9, 0),
    val frequency: Frequency = Frequency.Daily,
    val interval: Int = 1,
    val count: Int? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean get() = title.isNotBlank() && date != null && interval >= 1 && !isLoading
}

class CreateTodoViewModel(private val api: KadansApi) : ViewModel() {
    private val _state = MutableStateFlow(CreateTodoUiState())
    val state: StateFlow<CreateTodoUiState> = _state.asStateFlow()

    private val _created = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val created: SharedFlow<String> = _created.asSharedFlow()

    fun update(transform: (CreateTodoUiState) -> CreateTodoUiState) =
        _state.update { transform(it).copy(error = null) }

    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return

        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val timeZone = TimeZone.currentSystemDefault()
                val todo = when (current.mode) {
                    TodoMode.OneTime -> api.todos.createOneTime(buildOneTime(current, timeZone))
                    TodoMode.Recurring -> api.todos.createRecurring(buildRecurring(current, timeZone))
                }
                _state.update { it.copy(isLoading = false) }
                _created.emit(todo.id)
            } catch (e: KadansApiException) {
                val details = e.problem?.errors?.mapNotNull { it.message }?.joinToString("\n")
                _state.update { it.copy(isLoading = false, error = details?.ifBlank { null } ?: e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = "Could not reach the server.") }
            }
        }
    }

    internal companion object {
        /** The picked wall-clock date/time is meant in the user's zone; the API takes instants. */
        fun startInstant(state: CreateTodoUiState, timeZone: TimeZone): Instant =
            LocalDateTime(state.date!!, state.time).toInstant(timeZone)

        fun buildOneTime(state: CreateTodoUiState, timeZone: TimeZone) = CreateOneTimeTodo(
            title = state.title.trim(),
            description = state.description.trim(),
            notificationEnabled = state.notify,
            dueDate = startInstant(state, timeZone),
        )

        fun buildRecurring(state: CreateTodoUiState, timeZone: TimeZone) = CreateRecurringTodo(
            title = state.title.trim(),
            description = state.description.trim(),
            notificationEnabled = state.notify,
            recurrenceRule = CreateRecurrenceRule(
                frequency = state.frequency,
                startDate = startInstant(state, timeZone),
                interval = state.interval,
                count = state.count,
                timeZone = timeZone.id,
            ),
        )
    }
}
