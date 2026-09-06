package app.kadans.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.api.model.TodoOccurrenceResponse
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
    val selected: LocalDate? = null,
    val today: LocalDate,
    val isLoading: Boolean = true,
    val error: String? = null,
    val errorCode: String? = null,
) {
    val selectedOccurrences: List<TodoOccurrenceResponse>
        get() = selected?.let { byDay[it] }.orEmpty()
}

class CalendarViewModel(private val api: KadansApi) : ViewModel() {
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
        viewModelScope.launch {
            try {
                val from = cells.first().date.atStartOfDayIn(timeZone)
                val to = cells.last().date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(timeZone)
                val byDay = api.todos.occurrencesBetween(from, to)
                    .groupBy { it.scheduledAt.toLocalDateTime(timeZone).date }
                _state.value = _state.value.copy(byDay = byDay, isLoading = false)
            } catch (e: KadansApiException) {
                _state.value = _state.value.copy(isLoading = false, error = e.message, errorCode = e.errorCode)
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = null, errorCode = "network")
            }
        }
    }
}
