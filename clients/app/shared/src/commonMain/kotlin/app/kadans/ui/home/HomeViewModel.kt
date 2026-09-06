package app.kadans.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.api.model.NotificationResponse
import app.kadans.api.model.TodoOccurrenceResponse
import app.kadans.api.model.TodoResponse
import app.kadans.realtime.KadansRealtime
import app.kadans.realtime.RealtimeEvent
import app.kadans.realtime.SystemAlerts
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

    data class Error(val message: String?, val code: String? = null) : HomeUiState
}

class HomeViewModel(
    private val api: KadansApi,
    private val realtime: KadansRealtime,
    private val alerts: SystemAlerts,
) : ViewModel() {
    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _loggedOut = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val loggedOut: SharedFlow<Unit> = _loggedOut.asSharedFlow()

    /** Live notifications pushed over the hub, surfaced as a snackbar. */
    private val _liveNotifications = MutableSharedFlow<NotificationResponse>(extraBufferCapacity = 4)
    val liveNotifications: SharedFlow<NotificationResponse> = _liveNotifications.asSharedFlow()

    init {
        // Home only exists with a session; the hub connection lives for as long as it does.
        realtime.start()
        alerts.start()
        viewModelScope.launch {
            realtime.events.collect { event ->
                if (event is RealtimeEvent.NotificationReceived) {
                    _liveNotifications.emit(event.notification)
                    quietRefresh()
                }
            }
        }
    }

    fun refresh() {
        _state.value = HomeUiState.Loading
        viewModelScope.launch { load() }
    }

    /** Reload without flashing the spinner — used when a realtime event lands behind the UI. */
    fun quietRefresh() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        try {
            val now = Clock.System.now()
            val todos = api.todos.list(pageSize = 50)
            val upcoming = api.todos.occurrencesBetween(now, now + 7.days)
                .filter { !it.isPreview }
            _state.value = HomeUiState.Content(todos, upcoming)
        } catch (e: KadansApiException) {
            if (e.httpStatus == 401) _loggedOut.emit(Unit)
            else _state.value = HomeUiState.Error(e.message, e.errorCode)
        } catch (e: Exception) {
            _state.value = HomeUiState.Error(null, "network")
        }
    }

    fun logout() {
        viewModelScope.launch {
            realtime.stop()
            runCatching { api.auth.logout() }
            _loggedOut.emit(Unit)
        }
    }
}
