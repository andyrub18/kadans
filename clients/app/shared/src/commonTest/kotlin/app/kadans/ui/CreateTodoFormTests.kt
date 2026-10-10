package app.kadans.ui

import app.kadans.api.model.ApiDayOfWeek
import app.kadans.api.model.Frequency
import app.kadans.i18n.CreoleStrings
import app.kadans.i18n.EnglishStrings
import app.kadans.i18n.FrenchStrings
import app.kadans.ui.todos.CreateTodoUiState
import app.kadans.ui.todos.CreateTodoViewModel
import app.kadans.ui.todos.DayKind
import app.kadans.ui.todos.DayRule
import app.kadans.ui.todos.EndMode
import app.kadans.ui.todos.Ordinal
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
    fun several_times_a_day_survive_from_daily_to_yearly() {
        val withTimes = recurring().copy(times = listOf(LocalTime(8, 0), LocalTime(20, 0)))

        listOf(Frequency.Daily, Frequency.Weekly, Frequency.Monthly, Frequency.Yearly).forEach {
            assertEquals(withTimes.times, CreateTodoViewModel.withFrequency(withTimes, it).times, "$it")
        }
        assertEquals(emptyList(), CreateTodoViewModel.withFrequency(withTimes, Frequency.Hourly).times)
        // Weekdays at 08:00 and 20:00: BYHOUR × BYMINUTE on a weekly rule too.
        val rule = CreateTodoViewModel.buildRecurring(CreateTodoViewModel.withFrequency(withTimes, Frequency.Weekly), portAuPrince).recurrenceRule
        assertEquals(listOf(8, 20), rule.byHour)
        assertEquals(listOf(0), rule.byMinute)
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

    private fun monthly(rule: DayRule) = recurring().copy(frequency = Frequency.Monthly, dayRule = rule) // from Mon 2027-01-04
    private fun yearly(rule: DayRule) = recurring().copy(frequency = Frequency.Yearly, dayRule = rule)
    private fun send(state: CreateTodoUiState) = CreateTodoViewModel.buildRecurring(state, portAuPrince).recurrenceRule

    @Test
    fun a_monthly_rule_by_date_sends_its_days_and_starts_on_the_first() {
        // Left alone, it is the date's own day: the 4th of each month.
        assertEquals(listOf(4), send(monthly(DayRule.ByDate)).byMonthDay)

        val payday = monthly(DayRule.ByDate).copy(monthDays = setOf(15, 1))
        val rule = send(payday)
        assertEquals(Frequency.Monthly, rule.frequency)
        assertEquals(listOf(1, 15), rule.byMonthDay)
        assertEquals(null, rule.byDayOfWeek)
        assertEquals(null, rule.bySetPos)
        assertEquals(LocalDate(2027, 1, 15), payday.firstDate)
        assertEquals(Instant.parse("2027-01-15T14:00:00Z"), rule.startDate)

        val lastDay = monthly(DayRule.ByDate).copy(monthDays = setOf(-1))
        assertEquals(listOf(-1), send(lastDay).byMonthDay)
        assertEquals(LocalDate(2027, 1, 31), lastDay.firstDate)
        assertEquals(LocalDate(2027, 2, 1), lastDay.copy(date = LocalDate(2027, 2, 1), monthDays = setOf(1, -1)).firstDate)
    }

    @Test
    fun by_day_of_the_week_starts_as_the_date_picked_falls() {
        val secondTuesday = monthly(DayRule.ByWeekday).copy(date = LocalDate(2027, 1, 12))
        assertEquals(Ordinal.Second, secondTuesday.effectiveOrdinal)
        assertEquals(DayKind.Tuesday, secondTuesday.effectiveKind)
        assertEquals(listOf(ApiDayOfWeek.Tuesday), send(secondTuesday).byDayOfWeek)
        assertEquals(listOf(2), send(secondTuesday).bySetPos)
        assertEquals(null, send(secondTuesday).byMonthDay)
        assertEquals(LocalDate(2027, 1, 12), secondTuesday.firstDate)

        // A fifth Friday is always the month's last one: offered as "the last".
        assertEquals(Ordinal.Last, monthly(DayRule.ByWeekday).copy(date = LocalDate(2027, 1, 29)).effectiveOrdinal)
    }

    @Test
    fun the_last_weekday_of_the_month() {
        val closing = monthly(DayRule.ByWeekday).copy(ordinal = Ordinal.Last, dayKind = DayKind.Weekday)

        val rule = send(closing)
        assertEquals(mondayFirstDays.take(5), rule.byDayOfWeek)
        assertEquals(listOf(-1), rule.bySetPos)
        assertEquals(LocalDate(2027, 1, 29), closing.firstDate) // the 30th and 31st are a weekend
        assertTrue(closing.fallsOn(LocalDate(2027, 2, 26)))
        assertFalse(closing.fallsOn(LocalDate(2027, 2, 25)))
    }

    @Test
    fun a_yearly_rule_picks_its_months() {
        val quarterly = yearly(DayRule.ByDate).copy(months = setOf(7, 1, 4, 10), monthDays = setOf(1))
        val rule = send(quarterly)
        assertEquals(Frequency.Yearly, rule.frequency)
        assertEquals(listOf(1, 4, 7, 10), rule.byMonth)
        assertEquals(listOf(1), rule.byMonthDay)
        assertEquals(LocalDate(2027, 4, 1), quarterly.firstDate)

        // Left alone, it is the date's month and day: once a year, as before.
        assertEquals(listOf(1), send(yearly(DayRule.ByDate)).byMonth)
        assertEquals(listOf(4), send(yearly(DayRule.ByDate)).byMonthDay)
        assertEquals(LocalDate(2027, 1, 4), yearly(DayRule.ByDate).firstDate)
    }

    @Test
    fun the_second_sunday_of_may_is_a_yearly_rule() {
        val mothersDay = yearly(DayRule.ByWeekday).copy(months = setOf(5), ordinal = Ordinal.Second, dayKind = DayKind.Sunday)

        val rule = send(mothersDay)
        assertEquals(Frequency.Yearly, rule.frequency)
        assertEquals(listOf(5), rule.byMonth)
        assertEquals(listOf(ApiDayOfWeek.Sunday), rule.byDayOfWeek)
        assertEquals(listOf(2), rule.bySetPos)
        assertEquals(LocalDate(2027, 5, 9), mothersDay.firstDate)
        assertEquals(Instant.parse("2027-05-09T13:00:00Z"), rule.startDate) // 09:00 in Port-au-Prince, UTC−4 in May
        assertTrue(mothersDay.copy(interval = 2).canSubmit)
    }

    @Test
    fun a_day_of_the_week_in_several_months_is_each_months_and_every_year() {
        val firstMonday = yearly(DayRule.ByWeekday).copy(months = setOf(6, 1), ordinal = Ordinal.First, dayKind = DayKind.Monday)

        // A yearly BYSETPOS would count across all the year's Mondays: a monthly rule kept to those months.
        val rule = send(firstMonday)
        assertEquals(Frequency.Monthly, rule.frequency)
        assertEquals(listOf(1, 6), rule.byMonth)
        assertEquals(listOf(1), rule.bySetPos)
        assertEquals(LocalDate(2027, 1, 4), firstMonday.firstDate)
        assertTrue(firstMonday.canSubmit)
        // Which can only be every year.
        assertTrue(firstMonday.copy(interval = 2).severalMonthsNeedEveryYear)
        assertFalse(firstMonday.copy(interval = 2).canSubmit)
    }

    @Test
    fun a_rule_that_never_falls_cannot_be_sent() {
        val thirtiethOfFebruary = yearly(DayRule.ByDate).copy(months = setOf(2), monthDays = setOf(30))

        assertEquals(null, thirtiethOfFebruary.firstDate)
        assertTrue(thirtiethOfFebruary.neverFalls)
        assertFalse(thirtiethOfFebruary.canSubmit)
        assertEquals(null, CreateTodoViewModel.ruleInWords(thirtiethOfFebruary, EnglishStrings, portAuPrince))
    }

    @Test
    fun the_last_day_and_month_picked_stay() {
        val byDate = monthly(DayRule.ByDate) // shows the 4th
        assertEquals(byDate, CreateTodoViewModel.toggleMonthDay(byDate, 4))
        assertEquals(setOf(4, 15), CreateTodoViewModel.toggleMonthDay(byDate, 15).effectiveMonthDays)
        assertEquals(setOf(15), CreateTodoViewModel.toggleMonthDay(CreateTodoViewModel.toggleMonthDay(byDate, 15), 4).effectiveMonthDays)

        val inJanuary = yearly(DayRule.ByDate)
        assertEquals(inJanuary, CreateTodoViewModel.toggleMonth(inJanuary, 1))
        assertEquals(setOf(1, 7), CreateTodoViewModel.toggleMonth(inJanuary, 7).effectiveMonths)
    }

    @Test
    fun the_form_reads_its_rule_back_as_it_is_filled() {
        val closing = monthly(DayRule.ByWeekday).copy(ordinal = Ordinal.Last, dayKind = DayKind.Weekday)

        assertEquals("Every month · the last weekday · 09:00", CreateTodoViewModel.ruleInWords(closing, EnglishStrings, portAuPrince))
        assertEquals(
            "Every month · the last weekday · 09:00 · 12 times",
            CreateTodoViewModel.ruleInWords(closing.copy(endMode = EndMode.AfterCount, count = 12), EnglishStrings, portAuPrince),
        )
        // An end date not yet picked is left out rather than guessed.
        assertEquals(
            "Chaque mois · le dernier jour de semaine · 09:00",
            CreateTodoViewModel.ruleInWords(closing.copy(endMode = EndMode.OnDate), FrenchStrings, portAuPrince),
        )
        assertEquals(null, CreateTodoViewModel.ruleInWords(closing.copy(date = null), EnglishStrings, portAuPrince))
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
