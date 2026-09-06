package app.kadans.ui.todos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.api.model.TodoResponse
import app.kadans.api.model.UpdateTodo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class EditTodoUiState(
    val todo: TodoResponse? = null,
    val title: String = "",
    val description: String = "",
    val notify: Boolean = false,
    val notifyBefore: Int? = 15,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
    val errorCode: String? = null,
) {
    val canSave: Boolean get() = todo != null && title.isNotBlank() && !isSaving
}

class EditTodoViewModel(private val api: KadansApi, private val todoId: String) : ViewModel() {
    private val _state = MutableStateFlow(EditTodoUiState())
    val state: StateFlow<EditTodoUiState> = _state.asStateFlow()

    private val _saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saved: SharedFlow<Unit> = _saved.asSharedFlow()

    init {
        viewModelScope.launch {
            try {
                val todo = api.todos.get(todoId)
                _state.value = EditTodoUiState(
                    todo = todo,
                    title = todo.title,
                    description = todo.description,
                    notify = todo.notificationEnabled,
                    notifyBefore = todo.notifyBeforeInMinutes,
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

    fun save() {
        val current = _state.value
        val todo = current.todo ?: return
        _state.value = current.copy(isSaving = true, error = null, errorCode = null)
        viewModelScope.launch {
            try {
                api.todos.update(
                    todoId,
                    UpdateTodo(
                        title = current.title.trim(),
                        description = current.description.trim(),
                        notificationEnabled = current.notify,
                        // Not editable here; resent so the update doesn't clear them.
                        pomodoroTemplateId = todo.pomodoroTemplateId,
                        recurrenceRule = null,
                        notifyBeforeInMinutes = current.notifyBefore,
                    ),
                )
                _saved.emit(Unit)
            } catch (e: KadansApiException) {
                _state.value = _state.value.copy(isSaving = false, error = e.message, errorCode = e.errorCode)
            } catch (e: Exception) {
                _state.value = _state.value.copy(isSaving = false, errorCode = "network")
            }
        }
    }
}
