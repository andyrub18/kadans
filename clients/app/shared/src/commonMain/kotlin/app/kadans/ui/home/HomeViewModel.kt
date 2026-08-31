package app.kadans.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.api.model.TodoOccurrenceResponse
import app.kadans.api.model.TodoResponse
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface HomeUiState {
    data object Loading : HomeUiState

    data class Content(
        val todos: List<TodoResponse>,
        val upcoming: List<TodoOccurrenceResponse>,
    ) : HomeUiState

    data class Error(val message: String) : HomeUiState
}

class HomeViewModel(private val api: KadansApi) : ViewModel() {
    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _loggedOut = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val loggedOut: SharedFlow<Unit> = _loggedOut.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = HomeUiState.Loading
        viewModelScope.launch {
            try {
                val now = Clock.System.now()
                val todos = api.todos.list(pageSize = 50)
                val upcoming = api.todos.occurrencesBetween(now, now + 7.days)
                    .filter { !it.isPreview }
                _state.value = HomeUiState.Content(todos, upcoming)
            } catch (e: KadansApiException) {
                if (e.httpStatus == 401) _loggedOut.emit(Unit)
                else _state.value = HomeUiState.Error(e.message ?: "Request failed")
            } catch (e: Exception) {
                _state.value = HomeUiState.Error("Could not reach the server.")
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            runCatching { api.auth.logout() }
            _loggedOut.emit(Unit)
        }
    }
}
