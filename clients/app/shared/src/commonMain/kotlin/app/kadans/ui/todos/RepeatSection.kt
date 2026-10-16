package app.kadans.ui.todos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

private enum class RepeatTime { Extra, Until }

/**
 * How a rule repeats and ends, as calendar apps offer it: the frequency and its step, the days (of the week, of the month
 * or "the second Tuesday"), the months, several times a day, and the end. Each picker starts from the date picked, so a
 * rule left alone falls on it. A todo's ([WhenSection]) and a budget rule's (the movement form): one builder, one engine.
 * [frequencies] are those offered (money moves once a day at most), [severalTimes] offers several times a day, and
 * [endTime] lets the last day end at a time rather than with the day. The caller says the rule in words.
 */
@Composable
internal fun RepeatSection(
    state: CreateTodoUiState,
    update: ((CreateTodoUiState) -> CreateTodoUiState) -> Unit,
    frequencies: List<Frequency> = Frequency.entries,
    severalTimes: Boolean = true,
    endTime: Boolean = true,
) {
    val s = LocalStrings.current
    var pickingEnd by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf<RepeatTime?>(null) }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        frequencies.forEach { f ->
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
            DayRule.ByDate -> {
                MonthDayPicker(state.effectiveMonthDays, onToggle = { day -> update { CreateTodoViewModel.toggleMonthDay(it, day) } })
                // "The 30th" skips February, as calendars do: say so, and that the last day is in every month.
                state.skippedMonthDay?.let { day ->
                    Text(s.repeat.shortMonths(day), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
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

    if (severalTimes && state.frequency in CreateTodoViewModel.SEVERAL_TIMES) {
        Text(s.timesThatDay, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.times.sorted().forEach { t ->
                InputChip(
                    selected = false,
                    onClick = { update { it.copy(times = it.times - t) } },
                    label = { Text(t.formatted() + "  ✕") },
                )
            }
            OutlinedButton(onClick = { pickingTime = RepeatTime.Extra }) { Text(s.addTime) }
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
                onPick = { pickingEnd = true },
                placeholder = s.pickLastDay,
                modifier = Modifier.fillMaxWidth(),
            )
            // A date alone ends the rule with that day; a time makes it a precise moment.
            if (endTime) {
                PickerField(
                    value = state.untilTime?.formatted() ?: s.endOfDay,
                    label = s.lastOccurrenceAt,
                    kind = PickerKind.Time,
                    onPick = { pickingTime = RepeatTime.Until },
                    extra = if (state.untilTime == null) null else {
                        { TextButton(onClick = { update { it.copy(untilTime = null) } }) { Text(s.endOfDay) } }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        EndMode.Never -> {}
    }

    if (pickingEnd) {
        // Between the first occurrence and ten years after it: what the server accepts.
        val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
        val first = state.firstDate ?: today
        val pickerState = rememberDatePickerState(selectableDates = DateRange(first, RuleLimits.latestEnd(first)))
        DatePickerDialog(
            onDismissRequest = { pickingEnd = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val picked = LocalDate.fromEpochDays((millis / 86_400_000L).toInt())
                        update { it.copy(untilDate = picked) }
                    }
                    pickingEnd = false
                }) { Text(s.ok) }
            },
            dismissButton = { TextButton(onClick = { pickingEnd = false }) { Text(s.cancel) } },
        ) { DatePicker(state = pickerState) }
    }

    when (pickingTime) {
        RepeatTime.Extra -> TimeDialog(
            state.time,
            onPick = { picked -> update { it.copy(times = (it.times + picked).distinct()) } },
            onDismiss = { pickingTime = null },
        )
        RepeatTime.Until -> TimeDialog(
            state.untilTime ?: state.time,
            onPick = { picked -> update { it.copy(untilTime = picked) } },
            onDismiss = { pickingTime = null },
        )
        null -> {}
    }
}
