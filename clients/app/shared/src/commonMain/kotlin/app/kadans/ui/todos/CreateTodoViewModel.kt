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
import app.kadans.i18n.StringsCatalog
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
import kotlinx.datetime.number
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
    /** Extra wall-clock times for "N times a day" (daily to yearly); empty = the single [time]. */
    val times: List<LocalTime> = emptyList(),
    /** Weekly only: the days picked ("Monday to Friday"). None picked = the first date's own day, a plain weekly rule. */
    val byDays: Set<ApiDayOfWeek> = emptySet(),
    /** Monthly and yearly: the day picked by its date or by its day of the week. */
    val dayRule: DayRule = DayRule.ByDate,
    /** By date: 1–31, -1 the last day. None picked = the date's own day. */
    val monthDays: Set<Int> = emptySet(),
    /** By day of the week: "the second" and "Tuesday". Null = as the date picked falls (9 October 2026: the second Friday). */
    val ordinal: Ordinal? = null,
    val dayKind: DayKind? = null,
    /** Yearly: 1–12. None picked = the date's own month. */
    val months: Set<Int> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val errorCode: String? = null,
) {
    val timesShareMinute: Boolean get() = times.map { it.minute }.distinct().size <= 1

    /** The days a weekly rule repeats on, as the day picker shows them: the ones picked, else the first date's. */
    val weekDays: Set<ApiDayOfWeek> get() = byDays.ifEmpty { setOfNotNull(date?.dayOfWeek?.toApi()) }

    /** What the pickers show, the date picked filling in what was not chosen: the rule then falls on that date. */
    val effectiveMonthDays: Set<Int> get() = monthDays.ifEmpty { setOfNotNull(date?.day) }
    val effectiveOrdinal: Ordinal get() = ordinal ?: date?.let(Ordinal::of) ?: Ordinal.First
    val effectiveKind: DayKind get() = dayKind ?: date?.let { DayKind.of(it.dayOfWeek.toApi()) } ?: DayKind.Monday
    val effectiveMonths: Set<Int> get() = months.ifEmpty { setOfNotNull(date?.month?.number) }

    /** Whether the rule can fall on [day], its time aside. */
    fun fallsOn(day: LocalDate): Boolean = when (frequency) {
        Frequency.Weekly -> day.dayOfWeek.toApi() in weekDays
        Frequency.Monthly -> onPickedDay(day)
        Frequency.Yearly -> day.month.number in effectiveMonths && onPickedDay(day)
        Frequency.Minutely, Frequency.Hourly, Frequency.Daily -> true
    }

    private fun onPickedDay(day: LocalDate): Boolean = when (dayRule) {
        DayRule.ByDate -> isMonthDay(day, effectiveMonthDays)
        DayRule.ByWeekday -> isNthOfMonth(day, effectiveOrdinal, effectiveKind)
    }

    /**
     * The day of the first occurrence: the first day from [date] on that the rule falls on ("Monday to Friday" picked on a
     * Saturday starts on Monday, "the last weekday" on the month's last one). RFC 5545 wants the start to be an
     * occurrence, and COUNT counts from it. Null when the rule falls on no day within the ten years a rule may run.
     */
    val firstDate: LocalDate? by lazy {
        val picked = date ?: return@lazy null
        if (mode == TodoMode.OneTime) return@lazy picked
        generateSequence(picked) { it.plus(1, DateTimeUnit.DAY) }
            .takeWhile { it <= RuleLimits.latestEnd(picked) }
            .firstOrNull(::fallsOn)
    }

    /** A date was picked, but the rule never falls ("the 30th of February"): said under the rule, and not sent. */
    val neverFalls: Boolean get() = mode == TodoMode.Recurring && date != null && firstDate == null

    /**
     * "The second Sunday" in several months is each month's second Sunday, which RFC 5545 can only say as a monthly rule
     * limited to those months (see [CreateTodoViewModel.dayParts]): every year, not every two.
     */
    val severalMonthsNeedEveryYear: Boolean
        get() = frequency == Frequency.Yearly && dayRule == DayRule.ByWeekday && effectiveMonths.size > 1 && interval > 1

    /** More repeats than the server accepts: shown under the field, and the form cannot be sent. */
    val countTooHigh: Boolean get() = endMode == EndMode.AfterCount && (count ?: 0) > RuleLimits.MAX_COUNT

    val endValid: Boolean
        get() = when (endMode) {
            EndMode.Never -> true
            EndMode.AfterCount -> (count ?: 0) in 1..RuleLimits.MAX_COUNT
            // The end is a moment, not a day: "every 2 hours until Friday 18:00". It cannot precede the first
            // time, nor come more than ten years after it (the server's limit; the date picker enforces it too).
            EndMode.OnDate -> untilDate != null && firstDate.let { first ->
                first == null ||
                    LocalDateTime(untilDate, untilClock) >= LocalDateTime(first, times.minOrNull() ?: time) &&
                    untilDate <= RuleLimits.latestEnd(first)
            }
        }

    /** The wall-clock time the rule ends at on [untilDate]. */
    val untilClock: LocalTime get() = untilTime ?: END_OF_DAY

    val canSubmit: Boolean
        get() = title.isNotBlank() && date != null && interval >= CreateTodoViewModel.minInterval(frequency) && timesShareMinute &&
            (mode == TodoMode.OneTime || (endValid && !neverFalls && !severalMonthsNeedEveryYear)) && !isLoading
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
        /** Every 5 minutes at most; every other frequency starts at 1. */
        fun minInterval(frequency: Frequency): Int = if (frequency == Frequency.Minutely) 5 else 1

        /** The frequencies a day's several times apply to (BYHOUR × BYMINUTE); minute and hourly rules take none. */
        val SEVERAL_TIMES = setOf(Frequency.Daily, Frequency.Weekly, Frequency.Monthly, Frequency.Yearly)

        /** Switching frequency keeps the interval when it is allowed, else moves it up to the smallest one. */
        fun withFrequency(state: CreateTodoUiState, frequency: Frequency): CreateTodoUiState = state.copy(
            frequency = frequency,
            interval = state.interval.coerceAtLeast(minInterval(frequency)),
            times = if (frequency in SEVERAL_TIMES) state.times else emptyList(),
        )

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

        /** The same for a day of the month (-1 the last day). */
        fun toggleMonthDay(state: CreateTodoUiState, day: Int): CreateTodoUiState {
            val days = state.effectiveMonthDays.let { if (day in it) it - day else it + day }
            return if (days.isEmpty()) state else state.copy(monthDays = days)
        }

        /** The same for a month (1–12). */
        fun toggleMonth(state: CreateTodoUiState, month: Int): CreateTodoUiState {
            val months = state.effectiveMonths.let { if (month in it) it - month else it + month }
            return if (months.isEmpty()) state else state.copy(months = months)
        }

        /** The rule's day parts (RFC 5545), and its frequency: "the second Sunday" in several months is a monthly rule. */
        internal data class DayParts(
            val frequency: Frequency,
            val byDay: List<ApiDayOfWeek>? = null,
            val byMonthDay: List<Int>? = null,
            val bySetPos: List<Int>? = null,
            val byMonth: List<Int>? = null,
        )

        internal fun dayParts(state: CreateTodoUiState): DayParts {
            fun DayParts.picked() = when (state.dayRule) {
                DayRule.ByDate -> copy(byMonthDay = sortedMonthDays(state.effectiveMonthDays))
                DayRule.ByWeekday -> copy(byDay = state.effectiveKind.days, bySetPos = listOf(state.effectiveOrdinal.setPos))
            }
            return when (state.frequency) {
                Frequency.Weekly -> DayParts(Frequency.Weekly, byDay = mondayFirstDays.filter { it in state.weekDays })
                Frequency.Monthly -> DayParts(Frequency.Monthly).picked()
                Frequency.Yearly -> {
                    val months = state.effectiveMonths.sorted()
                    // A yearly BYSETPOS counts across all the year's matching days ("the second Sunday" of March and June
                    // would be March's only). Limiting a monthly rule to those months makes it each month's second Sunday.
                    val each = if (state.dayRule == DayRule.ByWeekday && months.size > 1) Frequency.Monthly else Frequency.Yearly
                    DayParts(each, byMonth = months).picked()
                }
                Frequency.Minutely, Frequency.Hourly, Frequency.Daily -> DayParts(state.frequency)
            }
        }

        /** The rule as it would be sent, in words, once it falls on a date; an end still being filled in is left out. */
        fun ruleInWords(state: CreateTodoUiState, s: StringsCatalog, timeZone: TimeZone): String? {
            if (state.mode != TodoMode.Recurring || state.firstDate == null) return null
            val complete = if (state.endMode == EndMode.OnDate && state.untilDate == null) state.copy(endMode = EndMode.Never) else state
            return RuleSummary.describe(buildRecurring(complete, timeZone).recurrenceRule, s)
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
            val severalTimes = state.frequency in SEVERAL_TIMES && state.times.size > 1
            val days = dayParts(state)
            return CreateRecurringTodo(
                title = state.title.trim(),
                description = state.description.trim(),
                notificationEnabled = state.notify,
                notifyBeforeInMinutes = state.notifyBefore,
                recurrenceRule = CreateRecurrenceRule(
                    frequency = days.frequency,
                    startDate = startInstant(state, timeZone),
                    interval = state.interval,
                    byHour = if (severalTimes) state.times.map { it.hour }.distinct().sorted() else null,
                    byMinute = if (severalTimes) listOf(state.times.first().minute) else null,
                    byDayOfWeek = days.byDay,
                    byMonthDay = days.byMonthDay,
                    bySetPos = days.bySetPos,
                    byMonth = days.byMonth,
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
