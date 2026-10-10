package app.kadans.ui.todos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.kadans.api.model.Frequency
import app.kadans.i18n.LocalStrings
import app.kadans.ui.DateRange
import app.kadans.ui.MonthDayPicker
import app.kadans.ui.MonthPicker
import app.kadans.ui.PickerField
import app.kadans.ui.PickerKind
import app.kadans.ui.WeekDayPicker
import app.kadans.ui.capitalized
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

private enum class TimeTarget { Start, ExtraTime, Until }

private enum class DateTarget { Start, Until }

/**
 * When a todo happens: its date and time and, for a recurring one, how it repeats and ends, with the rule read back
 * in words. The create form and the edit screen both use it; [update] changes [state].
 */
@Composable
internal fun WhenSection(state: CreateTodoUiState, update: ((CreateTodoUiState) -> CreateTodoUiState) -> Unit) {
    val s = LocalStrings.current
    val zone = TimeZone.currentSystemDefault()
    val now = Clock.System.now()
    var dateTarget by remember { mutableStateOf<DateTarget?>(null) }
    var timeTarget by remember { mutableStateOf<TimeTarget?>(null) }

    // Tapped anywhere, the fields open their pickers: the value keeps the room a "Pick" button used to take.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        PickerField(
            value = state.date?.toString() ?: "",
            label = if (state.mode == TodoMode.OneTime) s.dueDate else s.firstOn,
            kind = PickerKind.Date,
            onPick = { dateTarget = DateTarget.Start },
            placeholder = s.pickADate,
            modifier = Modifier.weight(1.4f),
        )
        if (state.times.isEmpty()) {
            PickerField(
                value = state.time.formatted(),
                label = s.timeLabel,
                kind = PickerKind.Time,
                onPick = { timeTarget = TimeTarget.Start },
                modifier = Modifier.weight(1f),
            )
        }
    }
    if (CreateTodoViewModel.startPassed(state, zone, now)) {
        Text(s.repeat.startPassed, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }

    if (state.mode == TodoMode.Recurring) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Frequency.entries.forEach { f ->
                FilterChip(
                    selected = state.frequency == f,
                    onClick = { update { CreateTodoViewModel.withFrequency(it, f) } },
                    label = { Text(s.frequencyName(f)) },
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Every 5 minutes at most: the stepper stops there (the server refuses more).
            OutlinedButton(
                onClick = { update { it.copy(interval = (it.interval - 1).coerceAtLeast(CreateTodoViewModel.minInterval(it.frequency))) } },
                enabled = state.interval > CreateTodoViewModel.minInterval(state.frequency),
            ) { Text("−") }
            Text(s.repeat.every(state.frequency, state.interval), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { update { it.copy(interval = it.interval + 1) } }) { Text("+") }
        }
        if (state.severalMonthsNeedEveryYear) {
            Text(s.repeat.severalMonthsEveryYear, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        // What the date picked does not say, the pickers do: each starts from that date (its weekday, its day of the
        // month, its month), so a rule left alone falls on it. A last choice cannot be dropped.
        if (state.frequency == Frequency.Weekly) {
            Text(s.onDaysLabel, style = MaterialTheme.typography.labelLarge)
            WeekDayPicker(state.weekDays, onToggle = { day -> update { CreateTodoViewModel.toggleDay(it, day) } })
        }

        if (state.frequency == Frequency.Yearly) {
            Text(s.repeat.monthsLabel, style = MaterialTheme.typography.labelLarge)
            MonthPicker(state.effectiveMonths, onToggle = { month -> update { CreateTodoViewModel.toggleMonth(it, month) } })
        }

        if (state.frequency == Frequency.Monthly || state.frequency == Frequency.Yearly) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.dayRule == DayRule.ByDate,
                    onClick = { update { it.copy(dayRule = DayRule.ByDate) } },
                    label = { Text(s.repeat.byDate) },
                )
                FilterChip(
                    selected = state.dayRule == DayRule.ByWeekday,
                    onClick = { update { it.copy(dayRule = DayRule.ByWeekday) } },
                    label = { Text(s.repeat.byWeekday) },
                )
            }
            when (state.dayRule) {
                DayRule.ByDate ->
                    MonthDayPicker(state.effectiveMonthDays, onToggle = { day -> update { CreateTodoViewModel.toggleMonthDay(it, day) } })
                DayRule.ByWeekday -> {
                    // "The second Tuesday", "the last weekday": which one, then which day.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Ordinal.entries.forEach { ordinal ->
                            FilterChip(
                                selected = state.effectiveOrdinal == ordinal,
                                onClick = { update { it.copy(ordinal = ordinal) } },
                                label = { Text(s.repeat.ordinals[ordinal.ordinal].capitalized()) },
                            )
                        }
                    }
                    WeekDayPicker(
                        selected = state.effectiveKind.days.singleOrNull()?.let { setOf(it) } ?: emptySet(),
                        onToggle = { day -> update { it.copy(dayKind = DayKind.of(day)) } },
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DayKind.Groups.forEach { kind ->
                            FilterChip(
                                selected = state.effectiveKind == kind,
                                onClick = { update { it.copy(dayKind = kind) } },
                                label = { Text(RuleSummary.kindName(kind, s).capitalized()) },
                            )
                        }
                    }
                }
            }
        }

        if (state.frequency in CreateTodoViewModel.SEVERAL_TIMES) {
            Text(s.timesThatDay, style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.times.sorted().forEach { t ->
                    InputChip(
                        selected = false,
                        onClick = { update { it.copy(times = it.times - t) } },
                        label = { Text(t.formatted() + "  ✕") },
                    )
                }
                OutlinedButton(onClick = { timeTarget = TimeTarget.ExtraTime }) { Text(s.addTime) }
            }
            if (!state.timesShareMinute) {
                Text(s.sameMinuteError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        Text(s.ends, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.endMode == EndMode.Never,
                onClick = { update { it.copy(endMode = EndMode.Never) } },
                label = { Text(s.endNever) },
            )
            FilterChip(
                selected = state.endMode == EndMode.AfterCount,
                onClick = { update { it.copy(endMode = EndMode.AfterCount) } },
                label = { Text(s.endAfterCount) },
            )
            FilterChip(
                selected = state.endMode == EndMode.OnDate,
                onClick = { update { it.copy(endMode = EndMode.OnDate) } },
                label = { Text(s.endOnDate) },
            )
        }
        when (state.endMode) {
            EndMode.AfterCount ->
                OutlinedTextField(
                    value = state.count?.toString() ?: "",
                    onValueChange = { v -> update { it.copy(count = RuleLimits.countInput(v)) } },
                    label = { Text(s.howManyTimes) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = state.countTooHigh,
                    supportingText = if (state.countTooHigh) ({ Text(s.repeat.countLimit) }) else null,
                    modifier = Modifier.fillMaxWidth(),
                )
            EndMode.OnDate -> {
                PickerField(
                    value = state.untilDate?.toString() ?: "",
                    label = s.lastOccurrenceOn,
                    kind = PickerKind.Date,
                    onPick = { dateTarget = DateTarget.Until },
                    placeholder = s.pickLastDay,
                    modifier = Modifier.fillMaxWidth(),
                )
                // A date alone ends the rule with that day; a time makes it a precise moment.
                PickerField(
                    value = state.untilTime?.formatted() ?: s.endOfDay,
                    label = s.lastOccurrenceAt,
                    kind = PickerKind.Time,
                    onPick = { timeTarget = TimeTarget.Until },
                    extra = if (state.untilTime == null) null else {
                        { TextButton(onClick = { update { it.copy(untilTime = null) } }) { Text(s.endOfDay) } }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            EndMode.Never -> {}
        }

        // The rule as it will be sent, in words, and its first moment when that is not the date and time picked.
        val words = remember(state, s) { CreateTodoViewModel.ruleInWords(state, s, zone, now) }
        if (words != null) {
            Text(words, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        val first = CreateTodoViewModel.firstStart(state, zone, now)
        val picked = state.date?.let { LocalDateTime(it, CreateTodoViewModel.effectiveTime(state)) }
        if (first != null && first != picked) {
            Text(
                s.repeat.firstTime(RuleSummary.day(first.date, s) + " " + first.time.formatted()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.neverFalls) {
            Text(s.repeat.neverFalls, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }

    val pickingDate = dateTarget
    if (pickingDate != null) {
        // Only dates the server accepts: a start from today on, an end between the first occurrence and ten years after it.
        val today = remember { Clock.System.todayIn(zone) }
        val first = if (pickingDate == DateTarget.Until) state.firstDate ?: today else today
        val last = if (pickingDate == DateTarget.Until) RuleLimits.latestEnd(first) else null
        val pickerState = rememberDatePickerState(selectableDates = DateRange(first, last))
        DatePickerDialog(
            onDismissRequest = { dateTarget = null },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val picked = LocalDate.fromEpochDays((millis / 86_400_000L).toInt())
                        update {
                            when (pickingDate) {
                                DateTarget.Start -> it.copy(date = picked)
                                DateTarget.Until -> it.copy(untilDate = picked)
                            }
                        }
                    }
                    dateTarget = null
                }) { Text(s.ok) }
            },
            dismissButton = { TextButton(onClick = { dateTarget = null }) { Text(s.cancel) } },
        ) { DatePicker(state = pickerState) }
    }

    val target = timeTarget
    if (target != null) {
        val initial = if (target == TimeTarget.Until) state.untilTime ?: state.time else state.time
        val timeState = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { timeTarget = null },
            confirmButton = {
                TextButton(onClick = {
                    val picked = LocalTime(timeState.hour, timeState.minute)
                    update {
                        when (target) {
                            TimeTarget.Start -> it.copy(time = picked)
                            TimeTarget.ExtraTime -> it.copy(times = (it.times + picked).distinct())
                            TimeTarget.Until -> it.copy(untilTime = picked)
                        }
                    }
                    timeTarget = null
                }) { Text(s.ok) }
            },
            dismissButton = { TextButton(onClick = { timeTarget = null }) { Text(s.cancel) } },
            text = { TimePicker(state = timeState) },
        )
    }
}

internal fun LocalTime.formatted(): String =
    hour.toString().padStart(2, '0') + ":" + minute.toString().padStart(2, '0')
