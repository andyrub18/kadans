package app.kadans.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.api.model.TodoOccurrenceResponse
import app.kadans.calendar.PhoneCalendar
import app.kadans.calendar.PhoneEvent
import app.kadans.calendar.PhoneEvents
import app.kadans.sync.DataArea
import app.kadans.sync.DataChanges
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

data class CalendarUiState(
    val year: Int,
    val month: Int,
    val cells: List<MonthCell> = emptyList(),
    val byDay: Map<LocalDate, List<TodoOccurrenceResponse>> = emptyMap(),
    /** The phone's own events on the days shown, where the person turned that on; never from the server. */
    val eventsByDay: Map<LocalDate, List<PhoneEvent>> = emptyMap(),
    val selected: LocalDate? = null,
    val today: LocalDate,
    val isLoading: Boolean = true,
    val error: String? = null,
    val errorCode: String? = null,
) {
    val selectedOccurrences: List<TodoOccurrenceResponse>
        get() = selected?.let { byDay[it] }.orEmpty()

    val selectedEvents: List<PhoneEvent>
        get() = selected?.let { eventsByDay[it] }.orEmpty()
}

class CalendarViewModel(
    private val api: KadansApi,
    private val dataChanges: DataChanges,
    private val phoneCalendar: PhoneCalendar,
) : ViewModel() {
    private val timeZone = TimeZone.currentSystemDefault()

    private val _state: MutableStateFlow<CalendarUiState>
    val state: StateFlow<CalendarUiState>

    init {
        val today = Clock.System.now().toLocalDateTime(timeZone).date
        _state = MutableStateFlow(
            CalendarUiState(year = today.year, month = today.month.number, today = today, selected = today)
        )
        state = _state.asStateFlow()
        load()
        // Changed elsewhere (another device, a notification's button), or back in front: shown as it is now.
        viewModelScope.launch { dataChanges.of(DataArea.Todos).collect { fetch(quiet = true) } }
        // The phone's calendar was turned on or off, or its permission given: the month is shown with or without it.
        viewModelScope.launch { phoneCalendar.changes.collect { fetch(quiet = true) } }
    }

    fun previous() = move(previousMonth(_state.value.year, _state.value.month))

    fun next() = move(nextMonth(_state.value.year, _state.value.month))

    fun select(date: LocalDate) {
        _state.value = _state.value.copy(selected = date)
    }

    fun refresh() = load()

    private fun move(target: Pair<Int, Int>) {
        val (year, month) = target
        _state.value = _state.value.copy(year = year, month = month, selected = null)
        load()
    }

    private fun load() {
        val snapshot = _state.value
        val cells = monthGrid(snapshot.year, snapshot.month)
        _state.value = snapshot.copy(cells = cells, isLoading = true, error = null, errorCode = null)
        viewModelScope.launch { fetch(quiet = false) }
    }

    /** The month shown, from the server. [quiet]: nobody asked; a failure (offline) keeps what is shown. */
    private suspend fun fetch(quiet: Boolean) {
        val shown = _state.value
        if (shown.cells.isEmpty()) return
        try {
            val from = shown.cells.first().date.atStartOfDayIn(timeZone)
            val to = shown.cells.last().date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(timeZone)
            val occurrences = api.todos.occurrencesBetween(from, to)
            val byDay = occurrences.groupBy { it.scheduledAt.toLocalDateTime(timeZone).date }
            // The phone's own events for the same days, read on the phone (empty unless the person turned that on).
            val events = phoneCalendar.events(from, to, occurrences)
            val eventsByDay = PhoneEvents.byDay(events, shown.cells.first().date, shown.cells.last().date, timeZone)
            // Moved to another month meanwhile: that month's own load shows it.
            if (_state.value.year == shown.year && _state.value.month == shown.month)
                _state.value = _state.value.copy(byDay = byDay, eventsByDay = eventsByDay, isLoading = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: KadansApiException) {
            if (!quiet) _state.value = _state.value.copy(isLoading = false, error = e.message, errorCode = e.errorCode)
        } catch (e: Exception) {
            if (!quiet) _state.value = _state.value.copy(isLoading = false, error = null, errorCode = "network")
        }
    }
}
