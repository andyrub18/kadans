package app.kadans.ui.home

import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.TimeZone
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import app.kadans.ui.Dates
import app.kadans.api.model.OccurrenceStatus
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
import app.kadans.sync.DataArea
import app.kadans.sync.listen
import app.kadans.sync.DataChanges
import app.kadans.ui.shell.UnreadNotifications
import app.kadans.calendar.PhoneCalendar
import app.kadans.calendar.PhoneEvent
import app.kadans.quiet.FocusQuiet
import app.kadans.widget.TodayWidget
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
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

    /** [events]: the phone's own calendar over the same days, where the person turned that on; never from the server. */
    data class Content(
        val todos: List<TodoResponse>,
        val upcoming: List<TodoOccurrenceResponse>,
        val events: List<PhoneEvent> = emptyList(),
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
    private val dataChanges: DataChanges,
    private val unread: UnreadNotifications,
    private val todayWidget: TodayWidget,
    private val focusQuiet: FocusQuiet,
    private val phoneCalendar: PhoneCalendar,
) : ViewModel() {
    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _loggedOut = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val loggedOut: SharedFlow<Unit> = _loggedOut.asSharedFlow()

    /** Live notifications pushed over the hub, surfaced as a snackbar. */
    private val _liveNotifications = MutableSharedFlow<NotificationResponse>(extraBufferCapacity = 4)
    val liveNotifications: SharedFlow<NotificationResponse> = _liveNotifications.asSharedFlow()

    /** A todo could not be marked done (offline, signed out): the screen says so. */
    private val _tickFailed = MutableSharedFlow<KadansApiException?>(extraBufferCapacity = 4)
    val tickFailed: SharedFlow<KadansApiException?> = _tickFailed.asSharedFlow()

    /** The circles pressed on Home: shown done at once, sent after the few seconds an undo has. */
    private val ticks = Ticks(
        scope = viewModelScope,
        send = { id -> api.todos.completeOccurrence(id) },
        sent = { _, failure ->
            if (failure != null) _tickFailed.emit(failure as? KadansApiException)
            // Done or not, the list says what the server holds; the widget has the same day.
            load(quiet = true)
            todayWidget.refreshSoon()
        },
    )
    val ticked: StateFlow<Set<String>> = ticks.ticked

    fun tick(occurrenceId: String) = ticks.tick(occurrenceId)

    fun untick(occurrenceId: String) = ticks.undo(occurrenceId)

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
            val open = _access.first { it != HomeAccess.Checking } == HomeAccess.Open
            if (open) localReminders.refresh() else localReminders.forget()
            // Do Not Disturb during focus follows the account's sessions past the paywall only, as reminders ring.
            focusQuiet.paywall(closed = !open)
            // Home opens: someone just signed in, or passed or met the paywall. The home-screen widget finds out now,
            // not at its next hour (it asks the server itself whether the account is locked).
            todayWidget.refreshSoon()
        }
        viewModelScope.listen(realtime.events) { event ->
            if (event is RealtimeEvent.NotificationReceived) {
                // A reminder, where this device shows reminders itself, is a system notification (SystemAlerts):
                // here it only updates the bell.
                val reminder = LocalReminders.reminderOf(event.notification)
                if (reminder == null || !localReminders.showsReminders()) _liveNotifications.emit(event.notification)
                unread.arrived() // the bell at once; quietRefresh below replaces it with the server's count
                quietRefresh()
            }
        }
        // Done, moved or added elsewhere (another device, a notification's button), or back in front: shown as it is now.
        dataChanges.reloadOn(viewModelScope, DataArea.Todos) { load(quiet = true) }
        // The phone's calendar was turned on or off, or its permission given: the days are shown with or without it.
        viewModelScope.listen(phoneCalendar.changes) { load(quiet = true) }
    }

    fun refresh() {
        _state.value = HomeUiState.Loading
        viewModelScope.launch { load() }
    }

    /** Reload without flashing the spinner — used when a realtime event lands behind the UI. */
    fun quietRefresh() {
        viewModelScope.launch { load(quiet = true) }
    }

    /** [quiet]: nobody asked; a failure (offline) keeps what is shown rather than turning Home into an error. */
    private suspend fun load(quiet: Boolean = false) {
        try {
            val now = Clock.System.now()
            val todos = api.todos.list(pageSize = 50)
            // From the day's first minute: what was done today stays in sight, and the day's progress counts it.
            val zone = TimeZone.currentSystemDefault()
            val dayStart = Dates.today(zone).atStartOfDayIn(zone)
            val upcoming = api.todos.occurrencesBetween(dayStart, now + 7.days, withCompleted = true)
                .filter { !it.isPreview && it.status != OccurrenceStatus.Cancelled }
            // The phone's own events for the same week, read on the phone (empty unless the person turned that on).
            val events = phoneCalendar.events(now, now + 7.days, upcoming)
            _state.value = HomeUiState.Content(todos, upcoming, events)
            // The badge is a nicety: never let it turn a loaded Home into an error.
            runCatching { api.notifications.unreadCount() }.onSuccess(unread::set)
        } catch (e: CancellationException) {
            throw e
        } catch (e: KadansApiException) {
            if (e.httpStatus == 401) _loggedOut.emit(Unit)
            else if (!(quiet && _state.value is HomeUiState.Content)) _state.value = HomeUiState.Error(e.message, e.errorCode)
        } catch (e: Exception) {
            if (!(quiet && _state.value is HomeUiState.Content)) _state.value = HomeUiState.Error(null, "network")
        }
    }

    /** Home is gone for good (signed out, the app closed): what was ticked and not yet sent is sent now. */
    override fun onCleared() {
        val left = ticks.abandon()
        if (left.isNotEmpty()) {
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                left.forEach { id -> runCatching { api.todos.completeOccurrence(id) } }
            }
        }
        super.onCleared()
    }
}
