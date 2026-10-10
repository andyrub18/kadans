package app.kadans.ui.todos

import app.kadans.api.model.Frequency
import app.kadans.api.model.RecurrenceRuleResponse
import app.kadans.ui.mondayFirstDays
import app.kadans.ui.toApi
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime

/**
 * The edit screen's rule, read from the stored one: what the form would show to make it again, from [firstOn] (the day of
 * the next occurrence nobody touched), with what is left of a counted rule. The server keeps every occurrence someone
 * moved, completed or annotated (ARCHITECTURE → Recurrence).
 */
internal object RuleDraft {
    class Draft(val state: CreateTodoUiState, val worded: Boolean)

    fun of(rule: RecurrenceRuleResponse, firstOn: LocalDate): Draft {
        val zone = runCatching { TimeZone.of(rule.timeZoneId) }.getOrDefault(TimeZone.UTC)
        val start = rule.startDate.toLocalDateTime(zone)
        val until = rule.until?.toLocalDateTime(zone)
        val base = CreateTodoUiState(
            mode = TodoMode.Recurring,
            date = firstOn,
            time = LocalTime(start.hour, start.minute),
            frequency = rule.frequency,
            interval = rule.interval,
            endMode = when {
                rule.count != null -> EndMode.AfterCount
                until != null -> EndMode.OnDate
                else -> EndMode.Never
            },
            // Started afresh, a counted rule keeps the times it had left, so it ends where it would have.
            count = rule.remaining ?: rule.count,
            untilDate = until?.date,
            untilTime = until?.time?.takeUnless { it.hour == END_OF_DAY.hour && it.minute == END_OF_DAY.minute }
                ?.let { LocalTime(it.hour, it.minute) },
        )
        val parts = RuleSummary.parts(rule)?.takeIf { RuleSummary.canWord(rule) } ?: return Draft(base, worded = false)

        // Several times a day: the cross product of hours and minutes (the form keeps them on one minute).
        val clock = parts.byHour?.let { hours -> hours.flatMap { hour -> (parts.byMinute ?: listOf(start.minute)).map { LocalTime(hour, it) } } }
            ?.distinct()?.sorted()
        val withTimes = when {
            clock == null -> base
            clock.size == 1 -> base.copy(time = clock.single())
            else -> base.copy(time = clock.first(), times = clock)
        }

        val days = parts.byDay?.toSet()
        val months = parts.byMonth?.toSet() ?: setOf(start.month.number)
        val onPicked = if (parts.bySetPos != null) {
            withTimes.copy(
                dayRule = DayRule.ByWeekday,
                ordinal = Ordinal.ofSetPos(parts.bySetPos.single()),
                dayKind = days?.let { DayKind.ofDays(it) },
            )
        } else {
            withTimes.copy(dayRule = DayRule.ByDate, monthDays = parts.byMonthDay?.toSet() ?: setOf(start.day))
        }
        val state = when (rule.frequency) {
            // A daily rule kept to some days is the weekly one the form makes ("every day, Monday to Friday").
            Frequency.Daily ->
                if (days == null) withTimes
                else if (rule.interval == 1) withTimes.copy(frequency = Frequency.Weekly, byDays = days)
                else return Draft(base, worded = false)
            Frequency.Weekly -> withTimes.copy(byDays = days ?: setOf(start.date.dayOfWeek.toApi()))
            // A monthly rule kept to some months is how the form sends "the second Sunday" of several months each year.
            Frequency.Monthly -> if (parts.byMonth != null) onPicked.copy(frequency = Frequency.Yearly, months = months) else onPicked
            Frequency.Yearly -> onPicked.copy(months = months)
            Frequency.Minutely, Frequency.Hourly -> withTimes
        }
        return Draft(state.copy(byDays = mondayFirstDays.filter { it in state.byDays }.toSet()), worded = true)
    }
}
