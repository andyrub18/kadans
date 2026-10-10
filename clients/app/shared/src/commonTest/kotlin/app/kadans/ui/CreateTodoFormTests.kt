package app.kadans.ui

import app.kadans.api.model.ApiDayOfWeek
import app.kadans.api.model.Frequency
import app.kadans.i18n.CreoleStrings
import app.kadans.i18n.EnglishStrings
import app.kadans.i18n.FrenchStrings
import app.kadans.ui.todos.CreateTodoUiState
import app.kadans.ui.todos.CreateTodoViewModel
import app.kadans.ui.todos.EndMode
import app.kadans.ui.todos.ReminderLeads
import app.kadans.ui.todos.TodoMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

/** The create form keeps within the server's limits and sends the reminder lead the person chose. */
class CreateTodoFormTests {
    private val portAuPrince = TimeZone.of("America/Port-au-Prince")

    private fun recurring() = CreateTodoUiState(
        title = "Water the plants",
        mode = TodoMode.Recurring,
        date = LocalDate(2027, 1, 4),
        time = LocalTime(9, 0),
    )

    @Test
    fun the_reminder_lead_chosen_is_the_one_sent() {
        assertEquals(15, CreateTodoUiState().notifyBefore)
        val state = recurring().copy(notifyBefore = 60)

        assertEquals(60, CreateTodoViewModel.buildRecurring(state, portAuPrince).notifyBeforeInMinutes)
        assertEquals(60, CreateTodoViewModel.buildOneTime(state.copy(mode = TodoMode.OneTime), portAuPrince).notifyBeforeInMinutes)
        assertEquals(0, CreateTodoViewModel.buildOneTime(state.copy(notifyBefore = 0), portAuPrince).notifyBeforeInMinutes)
    }

    @Test
    fun the_lead_choices_are_the_presets_plus_an_older_custom_lead() {
        assertEquals(listOf(0, 5, 10, 15, 30, 60, 1440), ReminderLeads.options(15))
        assertEquals(listOf(0, 5, 10, 15, 20, 30, 60, 1440), ReminderLeads.options(20))
        assertEquals(listOf(0, 5, 10, 15, 30, 60, 1440, 2880), ReminderLeads.options(2880))
    }

    @Test
    fun every_n_minutes_starts_at_five() {
        assertEquals(5, CreateTodoViewModel.minInterval(Frequency.Minutely))
        assertEquals(1, CreateTodoViewModel.minInterval(Frequency.Hourly))

        val minutely = CreateTodoViewModel.withFrequency(recurring(), Frequency.Minutely)
        assertEquals(5, minutely.interval)
        assertTrue(minutely.canSubmit)
        assertFalse(minutely.copy(interval = 4).canSubmit)
        // A larger interval survives the switch; switching back keeps it too.
        assertEquals(10, CreateTodoViewModel.withFrequency(recurring().copy(interval = 10), Frequency.Minutely).interval)
        assertEquals(5, CreateTodoViewModel.withFrequency(minutely, Frequency.Hourly).interval)
    }

    @Test
    fun several_times_a_day_only_survive_on_daily() {
        val withTimes = recurring().copy(times = listOf(LocalTime(8, 0), LocalTime(20, 0)))

        assertEquals(withTimes.times, CreateTodoViewModel.withFrequency(withTimes, Frequency.Daily).times)
        assertEquals(emptyList(), CreateTodoViewModel.withFrequency(withTimes, Frequency.Weekly).times)
    }

    @Test
    fun the_repeat_count_is_capped_at_the_servers_limit() {
        val byCount = recurring().copy(endMode = EndMode.AfterCount)

        assertTrue(byCount.copy(count = 5_000).canSubmit)
        assertFalse(byCount.copy(count = 5_000).countTooHigh)
        assertTrue(byCount.copy(count = 5_001).countTooHigh)
        assertFalse(byCount.copy(count = 5_001).canSubmit)
        assertFalse(byCount.copy(count = 0).canSubmit)
        // Only an "after N times" end is checked.
        assertFalse(recurring().copy(endMode = EndMode.Never, count = 9_999).countTooHigh)
    }

    @Test
    fun an_end_date_is_within_ten_years_of_the_start() {
        val start = LocalDate(2027, 1, 4)
        val byDate = recurring().copy(endMode = EndMode.OnDate)

        // The server counts ten years from the first instant (09:00): ending at 23:59 ten years later is past it.
        assertEquals(LocalDate(2037, 1, 3), CreateTodoViewModel.latestEnd(start))
        assertTrue(byDate.copy(untilDate = LocalDate(2037, 1, 3)).canSubmit)
        assertFalse(byDate.copy(untilDate = LocalDate(2037, 1, 4)).canSubmit)
        assertFalse(byDate.copy(untilDate = LocalDate(2027, 1, 3)).canSubmit)
    }

    @Test
    fun a_weekly_rule_falls_on_the_first_dates_day_until_others_are_picked() {
        val weekly = recurring().copy(frequency = Frequency.Weekly) // 2027-01-04 is a Monday

        assertEquals(setOf(ApiDayOfWeek.Monday), weekly.weekDays)
        assertEquals(LocalDate(2027, 1, 4), weekly.firstDate)
        assertEquals(listOf(ApiDayOfWeek.Monday), CreateTodoViewModel.buildRecurring(weekly, portAuPrince).recurrenceRule.byDayOfWeek)
        // Other frequencies send no days, whatever was picked while on Weekly.
        val daily = weekly.copy(frequency = Frequency.Daily, byDays = setOf(ApiDayOfWeek.Friday))
        assertEquals(null, CreateTodoViewModel.buildRecurring(daily, portAuPrince).recurrenceRule.byDayOfWeek)
        assertEquals(LocalDate(2027, 1, 4), daily.firstDate)
    }

    @Test
    fun monday_to_friday_picked_on_a_saturday_starts_on_monday() {
        val workdays = setOf(ApiDayOfWeek.Friday, ApiDayOfWeek.Monday, ApiDayOfWeek.Wednesday, ApiDayOfWeek.Tuesday, ApiDayOfWeek.Thursday)
        val alarm = recurring().copy(frequency = Frequency.Weekly, date = LocalDate(2027, 1, 2), time = LocalTime(6, 30), byDays = workdays)

        assertEquals(LocalDate(2027, 1, 4), alarm.firstDate)
        val rule = CreateTodoViewModel.buildRecurring(alarm, portAuPrince).recurrenceRule
        // The start is the first occurrence (RFC 5545): Monday 06:30 in Port-au-Prince, UTC−5 in January.
        assertEquals(Instant.parse("2027-01-04T11:30:00Z"), rule.startDate)
        assertEquals(
            listOf(ApiDayOfWeek.Monday, ApiDayOfWeek.Tuesday, ApiDayOfWeek.Wednesday, ApiDayOfWeek.Thursday, ApiDayOfWeek.Friday),
            rule.byDayOfWeek,
        )
        assertEquals(Frequency.Weekly, rule.frequency)
    }

    @Test
    fun a_day_chip_toggles_from_what_is_shown_and_the_last_day_stays() {
        val weekly = recurring().copy(frequency = Frequency.Weekly) // shows Monday, the first date's day

        val withTuesday = CreateTodoViewModel.toggleDay(weekly, ApiDayOfWeek.Tuesday)
        assertEquals(setOf(ApiDayOfWeek.Monday, ApiDayOfWeek.Tuesday), withTuesday.weekDays)
        val tuesdayOnly = CreateTodoViewModel.toggleDay(withTuesday, ApiDayOfWeek.Monday)
        assertEquals(setOf(ApiDayOfWeek.Tuesday), tuesdayOnly.weekDays)
        assertEquals(LocalDate(2027, 1, 5), tuesdayOnly.firstDate)
        assertEquals(tuesdayOnly, CreateTodoViewModel.toggleDay(tuesdayOnly, ApiDayOfWeek.Tuesday))
        // Before a date is picked nothing is shown, and the first tap picks that day.
        val noDate = weekly.copy(date = null)
        assertEquals(emptySet(), noDate.weekDays)
        assertEquals(setOf(ApiDayOfWeek.Sunday), CreateTodoViewModel.toggleDay(noDate, ApiDayOfWeek.Sunday).weekDays)
    }

    @Test
    fun an_end_before_the_first_chosen_day_is_refused() {
        val fromSaturday = recurring().copy(
            frequency = Frequency.Weekly,
            date = LocalDate(2027, 1, 2),
            byDays = setOf(ApiDayOfWeek.Monday, ApiDayOfWeek.Wednesday),
            endMode = EndMode.OnDate,
        )

        assertFalse(fromSaturday.copy(untilDate = LocalDate(2027, 1, 3)).canSubmit)
        assertTrue(fromSaturday.copy(untilDate = LocalDate(2027, 1, 4)).canSubmit)
        // Ten years count from that Monday too.
        assertTrue(fromSaturday.copy(untilDate = LocalDate(2037, 1, 3)).canSubmit)
        assertFalse(fromSaturday.copy(untilDate = LocalDate(2037, 1, 4)).canSubmit)
    }

    @Test
    fun the_lead_reads_naturally_in_each_language() {
        val leads = listOf(0, 5, 60, 90, 120, 1440, 2880)

        assertEquals(
            listOf("At the start", "5 min before", "1 h before", "90 min before", "2 h before", "1 day before", "2 days before"),
            leads.map(EnglishStrings.todoForm::reminderLead),
        )
        assertEquals(
            listOf("Au début", "5 min avant", "1 h avant", "90 min avant", "2 h avant", "1 jour avant", "2 jours avant"),
            leads.map(FrenchStrings.todoForm::reminderLead),
        )
        assertEquals(
            listOf("Lè l kòmanse", "5 min anvan", "1 èdtan anvan", "90 min anvan", "2 èdtan anvan", "1 jou anvan", "2 jou anvan"),
            leads.map(CreoleStrings.todoForm::reminderLead),
        )
    }
}
