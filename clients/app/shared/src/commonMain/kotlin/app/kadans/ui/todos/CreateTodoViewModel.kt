package app.kadans.ui.todos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.api.model.ApiDayOfWeek
import app.kadans.api.model.CreateOneTimeTodo
import app.kadans.api.model.CreateRecurrenceRule
import app.kadans.api.model.CreateRecurringTodo
import app.kadans.api.model.Frequency
import app.kadans.ui.mondayFirstDays
import app.kadans.ui.toApi
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
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

internal val END_OF_DAY = LocalTime(23, 59)

/** How a recurring todo ends — clients often know the last date, not the count (a treatment course). */
enum class EndMode { Never, AfterCount, OnDate }

data class CreateTodoUiState(
    val title: String = "",
    val description: String = "",
    val notify: Boolean = true,
    /** Minutes before the start the reminder comes ([ReminderLeads]). */
    val notifyBefore: Int = ReminderLeads.DEFAULT,
    val mode: TodoMode = TodoMode.OneTime,
    val date: LocalDate? = null,
    val time: LocalTime = LocalTime(9, 0),
    val frequency: Frequency = Frequency.Daily,
    val interval: Int = 1,
    val count: Int? = null,
    val endMode: EndMode = EndMode.Never,
    val untilDate: LocalDate? = null,
    /** Last moment on [untilDate]; null = the end of that day (what daily-and-slower rules want). */
    val untilTime: LocalTime? = null,
    /** Extra wall-clock times for "N times a day" (Daily only); empty = the single [time]. */
    val times: List<LocalTime> = emptyList(),
    /** Weekly only: the days picked ("Monday to Friday"). None picked = the first date's own day, a plain weekly rule. */
    val byDays: Set<ApiDayOfWeek> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val errorCode: String? = null,
) {
    val timesShareMinute: Boolean get() = times.map { it.minute }.distinct().size <= 1

    /** The days a weekly rule repeats on, as the day picker shows them: the ones picked, else the first date's. */
    val weekDays: Set<ApiDayOfWeek> get() = byDays.ifEmpty { setOfNotNull(date?.dayOfWeek?.toApi()) }

    /**
     * The day of the first occurrence. A weekly rule starts on its first chosen day from [date] on ("Monday to Friday"
     * picked on a Saturday starts on Monday): RFC 5545 wants the start to be an occurrence, and COUNT counts from it.
     */
    val firstDate: LocalDate?
        get() = if (mode == TodoMode.Recurring && frequency == Frequency.Weekly) {
            date?.let { picked -> (0..6).map { picked.plus(it, DateTimeUnit.DAY) }.first { it.dayOfWeek.toApi() in weekDays } }
        } else {
            date
        }

    /** More repeats than the server accepts: shown under the field, and the form cannot be sent. */
    val countTooHigh: Boolean get() = endMode == EndMode.AfterCount && (count ?: 0) > CreateTodoViewModel.MAX_COUNT

    val endValid: Boolean
        get() = when (endMode) {
            EndMode.Never -> true
            EndMode.AfterCount -> (count ?: 0) in 1..CreateTodoViewModel.MAX_COUNT
            // The end is a moment, not a day: "every 2 hours until Friday 18:00". It cannot precede the first
            // time, nor come more than ten years after it (the server's limit; the date picker enforces it too).
            EndMode.OnDate -> untilDate != null && firstDate.let { first ->
                first == null ||
                    LocalDateTime(untilDate, untilClock) >= LocalDateTime(first, times.minOrNull() ?: time) &&
                    untilDate <= CreateTodoViewModel.latestEnd(first)
            }
        }

    /** The wall-clock time the rule ends at on [untilDate]. */
    val untilClock: LocalTime get() = untilTime ?: END_OF_DAY

    val canSubmit: Boolean
        get() = title.isNotBlank() && date != null && interval >= CreateTodoViewModel.minInterval(frequency) && timesShareMinute &&
            (mode == TodoMode.OneTime || endValid) && !isLoading
}

class CreateTodoViewModel(private val api: KadansApi) : ViewModel() {
    private val _state = MutableStateFlow(CreateTodoUiState())
    val state: StateFlow<CreateTodoUiState> = _state.asStateFlow()

    private val _created = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val created: SharedFlow<String> = _created.asSharedFlow()

    fun update(transform: (CreateTodoUiState) -> CreateTodoUiState) =
        _state.update { transform(it).copy(error = null, errorCode = null) }

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
                _state.update { it.copy(isLoading = false, error = details?.ifBlank { null } ?: e.message, errorCode = if (details.isNullOrBlank()) e.errorCode else null) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = null, errorCode = "network") }
            }
        }
    }

    internal companion object {
        /** The server's limits for a new rule (RecurrenceSchedule): the form never offers more. */
        const val MAX_COUNT = 5_000
        const val MAX_YEARS = 10

        /** Every 5 minutes at most; every other frequency starts at 1. */
        fun minInterval(frequency: Frequency): Int = if (frequency == Frequency.Minutely) 5 else 1

        /** Switching frequency keeps the interval when it is allowed, else moves it up to the smallest one. */
        fun withFrequency(state: CreateTodoUiState, frequency: Frequency): CreateTodoUiState = state.copy(
            frequency = frequency,
            interval = state.interval.coerceAtLeast(minInterval(frequency)),
            times = if (frequency == Frequency.Daily) state.times else emptyList(),
        )

        /**
         * The last day a rule starting on [start] may end on. The server allows ten years from the first instant, so an
         * end on that same day ten years later, at the end of the day the form defaults to, would be refused.
         */
        fun latestEnd(start: LocalDate): LocalDate = start.plus(MAX_YEARS, DateTimeUnit.YEAR).minus(1, DateTimeUnit.DAY)

        fun effectiveTime(state: CreateTodoUiState): LocalTime =
            state.times.minOrNull() ?: state.time

        /** The first occurrence's wall-clock date/time is meant in the user's zone; the API takes instants. */
        fun startInstant(state: CreateTodoUiState, timeZone: TimeZone): Instant =
            LocalDateTime(state.firstDate!!, effectiveTime(state)).toInstant(timeZone)

        /** A tap on a day picks or drops it, starting from the days shown; the last one stays. */
        fun toggleDay(state: CreateTodoUiState, day: ApiDayOfWeek): CreateTodoUiState {
            val days = state.weekDays.let { if (day in it) it - day else it + day }
            return if (days.isEmpty()) state else state.copy(byDays = days)
        }

        fun buildOneTime(state: CreateTodoUiState, timeZone: TimeZone) = CreateOneTimeTodo(
            title = state.title.trim(),
            description = state.description.trim(),
            notificationEnabled = state.notify,
            notifyBeforeInMinutes = state.notifyBefore,
            dueDate = startInstant(state, timeZone),
        )

        fun buildRecurring(state: CreateTodoUiState, timeZone: TimeZone): CreateRecurringTodo {
            // "3 times a day": RRULE's BYHOUR×BYMINUTE is a cross product, so the UI keeps
            // all times on the same minute and we send the hour list once.
            val daily = state.frequency == Frequency.Daily && state.times.size > 1
            return CreateRecurringTodo(
                title = state.title.trim(),
                description = state.description.trim(),
                notificationEnabled = state.notify,
                notifyBeforeInMinutes = state.notifyBefore,
                recurrenceRule = CreateRecurrenceRule(
                    frequency = state.frequency,
                    startDate = startInstant(state, timeZone),
                    interval = state.interval,
                    byHour = if (daily) state.times.map { it.hour }.distinct().sorted() else null,
                    byMinute = if (daily) listOf(state.times.first().minute) else null,
                    byDayOfWeek = if (state.frequency == Frequency.Weekly) mondayFirstDays.filter { it in state.weekDays } else null,
                    count = if (state.endMode == EndMode.AfterCount) state.count else null,
                    // RRULE's UNTIL is inclusive. Without a picked time it is the end of that day in the
                    // user's zone, so the whole last day counts; with one, an occurrence at exactly that
                    // time is the last.
                    until = if (state.endMode == EndMode.OnDate)
                        LocalDateTime(state.untilDate!!, state.untilClock).toInstant(timeZone)
                    else null,
                    timeZone = timeZone.id,
                ),
            )
        }
    }
}
