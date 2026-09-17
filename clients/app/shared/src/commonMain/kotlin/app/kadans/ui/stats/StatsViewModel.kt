package app.kadans.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.api.model.PomodoroDayStats
import app.kadans.api.model.PomodoroPhaseType
import app.kadans.api.model.PomodoroRunResponse
import app.kadans.api.model.PomodoroStatsResponse
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

enum class StatsRange(val days: Int) { Week(7), Month(30), Quarter(90) }

/** What the screen shows, derived once from the server's answer. */
data class StatsSummary(
    val stats: PomodoroStatsResponse,
    /** One entry per calendar day of the range, oldest first — days without focus are zeros, not gaps. */
    val days: List<PomodoroDayStats>,
    val dailyAverageFocusMinutes: Int,
    val bestDay: PomodoroDayStats?,
) {
    val maxFocusMinutes: Int get() = days.maxOfOrNull { it.focusMinutes } ?: 0
}

sealed interface StatsUiState {
    val range: StatsRange

    data class Loading(override val range: StatsRange) : StatsUiState

    data class Content(override val range: StatsRange, val summary: StatsSummary) : StatsUiState

    data class Error(override val range: StatsRange, val message: String?, val code: String? = null) : StatsUiState
}

class StatsViewModel(private val api: KadansApi) : ViewModel() {
    private val _state = MutableStateFlow<StatsUiState>(StatsUiState.Loading(StatsRange.Week))
    val state: StateFlow<StatsUiState> = _state.asStateFlow()

    fun refresh() = load(_state.value.range)

    fun select(range: StatsRange) {
        if (range != _state.value.range || _state.value !is StatsUiState.Content) load(range)
    }

    private fun load(range: StatsRange) {
        _state.value = StatsUiState.Loading(range)
        viewModelScope.launch {
            try {
                val now = Clock.System.now()
                val stats = api.pomodoro.stats(from = now - range.days.days, to = now)
                // The server groups per day in the *account's* zone; lay the calendar out in that same zone.
                val zone = runCatching { TimeZone.of(stats.timeZoneId) }.getOrDefault(TimeZone.currentSystemDefault())
                val today = now.toLocalDateTime(zone).date
                if (_state.value.range == range) _state.value = StatsUiState.Content(range, summarize(stats, today, range.days))
            } catch (e: KadansApiException) {
                _state.value = StatsUiState.Error(range, e.message, e.errorCode)
            } catch (e: Exception) {
                _state.value = StatsUiState.Error(range, null, "network")
            }
        }
    }

    internal companion object {
        /** A continuous calendar ending [today]: the server only sends days that have data. */
        fun fillDays(perDay: List<PomodoroDayStats>, today: LocalDate, dayCount: Int): List<PomodoroDayStats> {
            val known = perDay.associateBy { it.date }
            val first = today.minus(DatePeriod(days = dayCount - 1))
            return (0 until dayCount).map { offset ->
                val date = first.plus(DatePeriod(days = offset))
                known[date] ?: PomodoroDayStats(date, focusMinutes = 0, breakMinutes = 0, completedRuns = 0)
            }
        }

        fun summarize(stats: PomodoroStatsResponse, today: LocalDate, dayCount: Int): StatsSummary {
            val days = fillDays(stats.perDay, today, dayCount)
            return StatsSummary(
                stats = stats,
                days = days,
                // Over the whole range, idle days included: that is what "per day" means to a person.
                dailyAverageFocusMinutes = if (dayCount > 0) days.sumOf { it.focusMinutes } / dayCount else 0,
                bestDay = days.filter { it.focusMinutes > 0 }.maxByOrNull { it.focusMinutes },
            )
        }

        /** "45 min", "2 h", "2 h 05 min" — reads the same in English, French and Kreyòl. */
        fun formatMinutes(minutes: Int): String {
            val hours = minutes / 60
            val rest = minutes % 60
            return when {
                hours == 0 -> "$rest min"
                rest == 0 -> "$hours h"
                else -> "$hours h " + rest.toString().padStart(2, '0') + " min"
            }
        }

        /** Focus actually done in a run: only completed focus phases count, across every lap. */
        fun focusMinutesOf(run: PomodoroRunResponse): Int =
            run.phases.filter { it.type == PomodoroPhaseType.Focus && it.completedAt != null }.sumOf { it.durationMinutes }

        fun lapsOf(run: PomodoroRunResponse): Int =
            if (run.cycleLength <= 0 || run.phases.isEmpty()) 1 else (run.phases.size + run.cycleLength - 1) / run.cycleLength
    }
}
