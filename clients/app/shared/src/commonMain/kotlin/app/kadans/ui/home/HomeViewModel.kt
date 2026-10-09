package app.kadans.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.api.model.NotificationResponse
import app.kadans.api.model.TodoOccurrenceResponse
import app.kadans.api.model.TodoResponse
import app.kadans.billing.SubscriptionGate
import app.kadans.profile.ProfileSync
import app.kadans.push.DeviceRegistrar
import app.kadans.realtime.KadansRealtime
import app.kadans.realtime.RealtimeEvent
import app.kadans.realtime.SystemAlerts
import app.kadans.reminders.LocalReminders
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface HomeUiState {
    data object Loading : HomeUiState

    data class Content(
        val todos: List<TodoResponse>,
        val upcoming: List<TodoOccurrenceResponse>,
    ) : HomeUiState

    data class Error(val message: String?, val code: String? = null) : HomeUiState
}

/** Whether this phone may show Home: asked of the server first (desktop is free and never waits). */
enum class HomeAccess { Checking, Open, Paywall }

class HomeViewModel(
    private val api: KadansApi,
    private val realtime: KadansRealtime,
    private val alerts: SystemAlerts,
    private val deviceRegistrar: DeviceRegistrar,
    private val profileSync: ProfileSync,
    private val gate: SubscriptionGate,
    private val localReminders: LocalReminders,
) : ViewModel() {
    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _loggedOut = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val loggedOut: SharedFlow<Unit> = _loggedOut.asSharedFlow()

    /** Live notifications pushed over the hub, surfaced as a snackbar. */
    private val _liveNotifications = MutableSharedFlow<NotificationResponse>(extraBufferCapacity = 4)
    val liveNotifications: SharedFlow<NotificationResponse> = _liveNotifications.asSharedFlow()

    /** Unread notifications, shown as the badge on the bell. */
    private val _unread = MutableStateFlow(0)
    val unread: StateFlow<Int> = _unread.asStateFlow()

    private val _access = MutableStateFlow(if (gate.appliesHere) HomeAccess.Checking else HomeAccess.Open)
    val access: StateFlow<HomeAccess> = _access.asStateFlow()

    init {
        if (_access.value == HomeAccess.Checking) {
            viewModelScope.launch {
                val paywall = gate.needsPaywall()
                // Behind the paywall nothing live is shown: the hub comes back with the next Home.
                if (paywall) realtime.stop()
                _access.value = if (paywall) HomeAccess.Paywall else HomeAccess.Open
            }
        }
        // Home only exists with a session; the hub connection lives for as long as it does.
        realtime.start()
        alerts.start()
        val registered = viewModelScope.launch { deviceRegistrar.register() }
        // The account follows this device: time zone (while followed) and language. Best-effort.
        val profiled = viewModelScope.launch { runCatching { profileSync.sync() } }
        // The reminders this phone rings itself: once it is registered and the account matches it (the reminders'
        // words follow its language and time zone), and only past the paywall.
        viewModelScope.launch {
            registered.join()
            profiled.join()
            if (_access.first { it != HomeAccess.Checking } == HomeAccess.Open) localReminders.refresh() else localReminders.forget()
        }
        viewModelScope.launch {
            realtime.events.collect { event ->
                if (event is RealtimeEvent.NotificationReceived) {
                    // A reminder this phone already rang itself: the hub's copy only updates the bell.
                    if (!rangHere(event.notification)) _liveNotifications.emit(event.notification)
                    _unread.value += 1 // instant; quietRefresh below replaces it with the server's count
                    quietRefresh()
                }
            }
        }
    }

    private fun rangHere(notification: NotificationResponse): Boolean {
        if (notification.kind != LocalReminders.DUE_KIND) return false
        val occurrenceId = notification.data?.get("occurrenceId") ?: return false
        val notifyAt = notification.data["notifyAt"]?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return false
        return localReminders.rangHere(occurrenceId, notifyAt)
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
            // The badge is a nicety: never let it turn a loaded Home into an error.
            runCatching { api.notifications.unreadCount() }.onSuccess { _unread.value = it }
        } catch (e: KadansApiException) {
            if (e.httpStatus == 401) _loggedOut.emit(Unit)
            else _state.value = HomeUiState.Error(e.message, e.errorCode)
        } catch (e: Exception) {
            _state.value = HomeUiState.Error(null, "network")
        }
    }

    internal companion object {
        /** A badge must stay a badge: past 99 the exact number stops mattering. */
        fun badgeText(unread: Int): String = if (unread > 99) "99+" else unread.toString()
    }

    fun logout() {
        viewModelScope.launch {
            realtime.stop()
            runCatching { api.auth.logout() }
            _loggedOut.emit(Unit)
        }
    }
}
