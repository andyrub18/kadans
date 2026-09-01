package app.kadans.ui.todos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.api.model.TodoOccurrenceResponse
import app.kadans.api.model.TodoResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface TodoDetailUiState {
    data object Loading : TodoDetailUiState

    data class Content(
        val todo: TodoResponse,
        val occurrences: List<TodoOccurrenceResponse>,
        val showHistory: Boolean,
        val hasActiveRun: Boolean,
        val actionError: String? = null,
    ) : TodoDetailUiState

    data class Error(val message: String) : TodoDetailUiState
}

class TodoDetailViewModel(private val api: KadansApi, private val todoId: String) : ViewModel() {
    private val _state = MutableStateFlow<TodoDetailUiState>(TodoDetailUiState.Loading)
    val state: StateFlow<TodoDetailUiState> = _state.asStateFlow()

    private var showHistory = false

    fun refresh() {
        viewModelScope.launch { load() }
    }

    fun toggleHistory() {
        showHistory = !showHistory
        refresh()
    }

    fun completeOccurrence(occurrenceId: String) = act { api.todos.completeOccurrence(occurrenceId) }

    fun cancelOccurrence(occurrenceId: String) = act { api.todos.cancelOccurrence(occurrenceId) }

    fun cancelTodo() = act { api.todos.cancel(todoId) }

    private fun act(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
                load()
            } catch (e: KadansApiException) {
                val current = _state.value
                if (current is TodoDetailUiState.Content)
                    _state.value = current.copy(actionError = e.message)
                else load()
            } catch (e: Exception) {
                load()
            }
        }
    }

    private suspend fun load() {
        try {
            val todo = api.todos.get(todoId)
            val occurrences =
                if (showHistory) api.todos.history(todoId, pageSize = 50)
                else api.todos.occurrences(todoId, pageSize = 50)
            val hasActiveRun = runCatching { api.pomodoro.activeRun(todoId) }.isSuccess
            _state.value = TodoDetailUiState.Content(todo, occurrences, showHistory, hasActiveRun)
        } catch (e: KadansApiException) {
            _state.value = TodoDetailUiState.Error(e.message ?: "Request failed")
        } catch (e: Exception) {
            _state.value = TodoDetailUiState.Error("Could not reach the server.")
        }
    }
}
