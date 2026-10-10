package app.kadans.ui

import app.kadans.api.model.ApiDayOfWeek
import app.kadans.api.model.Frequency
import app.kadans.api.model.RecurrenceRuleResponse
import app.kadans.i18n.CreoleStrings
import app.kadans.i18n.EnglishStrings
import app.kadans.i18n.FrenchStrings
import app.kadans.ui.todos.RuleSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/** A todo's rule and its occurrences read in words, in the person's time, in each language. */
class RuleSummaryTests {
    private val portAuPrince = TimeZone.of("America/Port-au-Prince")

    /** As the server answers: the RRULE as Ical.Net writes it, and the start (07:00 in Port-au-Prince, UTC−5 in January). */
    private fun rule(
        rrule: String,
        frequency: Frequency,
        interval: Int = 1,
        start: String = "2027-01-04T12:00:00Z",
        count: Int? = null,
        until: String? = null,
        isOneTime: Boolean = false,
    ) = RecurrenceRuleResponse(
        rrule = rrule,
        timeZoneId = portAuPrince.id,
        startDate = Instant.parse(start),
        frequency = frequency,
        interval = interval,
        count = count,
        until = until?.let(Instant::parse),
        isOneTime = isOneTime,
    )

    @Test
    fun monday_to_friday_reads_as_a_range_in_each_language() {
        val alarm = rule("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", Frequency.Weekly)

        assertEquals("Every week · Mon–Fri · 07:00", RuleSummary.describe(alarm, EnglishStrings, portAuPrince))
        assertEquals("Chaque semaine · lun–ven · 07:00", RuleSummary.describe(alarm, FrenchStrings, portAuPrince))
        assertEquals("Chak semèn · len–van · 07:00", RuleSummary.describe(alarm, CreoleStrings, portAuPrince))
    }

    @Test
    fun weekly_rules_name_their_days_and_how_they_end() {
        val everyOther = rule("FREQ=WEEKLY;INTERVAL=2;COUNT=10;BYDAY=MO,WE,FR", Frequency.Weekly, interval = 2, count = 10)
        assertEquals("Every 2 weeks · Mon, Wed, Fri · 07:00 · 10 times", RuleSummary.describe(everyOther, EnglishStrings, portAuPrince))
        assertEquals("Toutes les 2 semaines · lun, mer, ven · 07:00 · 10 fois", RuleSummary.describe(everyOther, FrenchStrings, portAuPrince))

        // A weekly rule made before days could be picked falls on its start's day.
        val plain = rule("FREQ=WEEKLY", Frequency.Weekly)
        assertEquals("Every week · Mon · 07:00", RuleSummary.describe(plain, EnglishStrings, portAuPrince))
    }

    @Test
    fun daily_times_and_the_end_read_in_the_rules_zone() {
        val pills = rule("FREQ=DAILY;BYHOUR=8,14,20;BYMINUTE=0", Frequency.Daily)
        assertEquals("Every day · 08:00, 14:00, 20:00", RuleSummary.describe(pills, EnglishStrings, portAuPrince))

        // The form's end without a time is the end of that day: the date alone. A picked time is kept.
        val endOfDay = rule("FREQ=DAILY;UNTIL=20270401T035900Z", Frequency.Daily, until = "2027-04-01T03:59:00Z")
        assertEquals("Every day · 07:00 · until 2027-03-31", RuleSummary.describe(endOfDay, EnglishStrings, portAuPrince))
        val atSix = rule("FREQ=HOURLY;INTERVAL=2;UNTIL=20270331T220000Z", Frequency.Hourly, interval = 2, until = "2027-03-31T22:00:00Z")
        assertEquals("Chak 2 èdtan · jiska 2027-03-31 18:00", RuleSummary.describe(atSix, CreoleStrings, portAuPrince))
    }

    @Test
    fun monthly_and_yearly_rules_name_their_day() {
        val rent = rule("FREQ=MONTHLY", Frequency.Monthly, start = "2027-03-15T13:00:00Z")
        assertEquals("Every month · on day 15 · 09:00", RuleSummary.describe(rent, EnglishStrings, portAuPrince))
        assertEquals("Chaque mois · le 15 · 09:00", RuleSummary.describe(rent, FrenchStrings, portAuPrince))

        val birthday = rule("FREQ=YEARLY", Frequency.Yearly, start = "2027-03-15T13:00:00Z")
        assertEquals("Chaque année · 15 mars · 09:00", RuleSummary.describe(birthday, FrenchStrings, portAuPrince))
    }

    @Test
    fun a_one_time_todo_reads_as_its_moment() {
        val once = rule("FREQ=DAILY;COUNT=1", Frequency.Daily, count = 1, isOneTime = true)

        assertEquals("One-time · Mon 2027-01-04 07:00", RuleSummary.describe(once, EnglishStrings, portAuPrince))
    }

    @Test
    fun monthly_days_read_as_dates() {
        val payday = rule("FREQ=MONTHLY;BYMONTHDAY=1,15", Frequency.Monthly)
        assertEquals("Every month · on days 1, 15 · 07:00", RuleSummary.describe(payday, EnglishStrings, portAuPrince))
        assertEquals("Chaque mois · les 1, 15 · 07:00", RuleSummary.describe(payday, FrenchStrings, portAuPrince))
        assertEquals("Chak mwa · jou 1, 15 yo · 07:00", RuleSummary.describe(payday, CreoleStrings, portAuPrince))

        assertEquals(
            "Every month · on the last day · 07:00",
            RuleSummary.describe(rule("FREQ=MONTHLY;BYMONTHDAY=-1", Frequency.Monthly), EnglishStrings, portAuPrince),
        )
        assertEquals(
            "Every month · on days 1, last · 07:00",
            RuleSummary.describe(rule("FREQ=MONTHLY;BYMONTHDAY=1,-1", Frequency.Monthly), EnglishStrings, portAuPrince),
        )
    }

    @Test
    fun a_position_reads_as_the_nth_day() {
        val closing = rule("FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1", Frequency.Monthly)
        assertEquals("Every month · the last weekday · 07:00", RuleSummary.describe(closing, EnglishStrings, portAuPrince))
        assertEquals("Chaque mois · le dernier jour de semaine · 07:00", RuleSummary.describe(closing, FrenchStrings, portAuPrince))
        assertEquals("Chak mwa · dènye jou travay · 07:00", RuleSummary.describe(closing, CreoleStrings, portAuPrince))

        assertEquals(
            "Every 2 months · the last Friday · 07:00",
            RuleSummary.describe(rule("FREQ=MONTHLY;INTERVAL=2;BYDAY=FR;BYSETPOS=-1", Frequency.Monthly, interval = 2), EnglishStrings, portAuPrince),
        )
        assertEquals(
            "Every month · the first day · 07:00",
            RuleSummary.describe(rule("FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR,SA,SU;BYSETPOS=1", Frequency.Monthly), EnglishStrings, portAuPrince),
        )
    }

    @Test
    fun yearly_rules_name_their_months() {
        val mothersDay = rule("FREQ=YEARLY;BYDAY=SU;BYMONTH=5;BYSETPOS=2", Frequency.Yearly)
        assertEquals("Every year · May · the second Sunday · 07:00", RuleSummary.describe(mothersDay, EnglishStrings, portAuPrince))
        assertEquals("Chaque année · mai · le deuxième dimanche · 07:00", RuleSummary.describe(mothersDay, FrenchStrings, portAuPrince))
        assertEquals("Chak ane · me · dezyèm dimanch · 07:00", RuleSummary.describe(mothersDay, CreoleStrings, portAuPrince))

        val quarterly = rule("FREQ=YEARLY;BYMONTH=1,4,7,10;BYMONTHDAY=1", Frequency.Yearly)
        assertEquals("Every year · January, April, July, October · on day 1 · 07:00", RuleSummary.describe(quarterly, EnglishStrings, portAuPrince))

        // The day left to the start (the 4th) is said too.
        assertEquals(
            "Every 2 years · March · on day 4 · 07:00",
            RuleSummary.describe(rule("FREQ=YEARLY;INTERVAL=2;BYMONTH=3", Frequency.Yearly, interval = 2), EnglishStrings, portAuPrince),
        )
        // How the form sends "the first Monday" of several months: it reads as the yearly rule it is.
        assertEquals(
            "Every year · January, June · the first Monday · 07:00",
            RuleSummary.describe(rule("FREQ=MONTHLY;BYDAY=MO;BYMONTH=1,6;BYSETPOS=1", Frequency.Monthly), EnglishStrings, portAuPrince),
        )
    }

    @Test
    fun a_part_the_app_cannot_word_shows_the_rule_itself() {
        listOf(
            "FREQ=MONTHLY;BYDAY=1MO" to Frequency.Monthly, // an ordinal inside BYDAY
            "FREQ=MONTHLY;BYDAY=MO" to Frequency.Monthly, // every Monday of the month
            "FREQ=MONTHLY;BYDAY=MO,WE;BYSETPOS=1" to Frequency.Monthly, // the first of Monday and Wednesday
            "FREQ=YEARLY;BYMONTHDAY=15" to Frequency.Yearly, // the 15th of every month, in a yearly rule
            "FREQ=YEARLY;BYWEEKNO=20;BYDAY=MO" to Frequency.Yearly,
            "FREQ=MONTHLY;BYMONTHDAY=-2" to Frequency.Monthly,
        ).forEach { (rrule, frequency) ->
            assertEquals(rrule, RuleSummary.describe(rule(rrule, frequency), EnglishStrings, portAuPrince))
        }
    }

    @Test
    fun a_device_in_another_zone_is_told_whose_clock_the_times_are() {
        val alarm = rule("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", Frequency.Weekly)

        assertEquals(
            "Every week · Mon–Fri · 07:00 · America/Port-au-Prince",
            RuleSummary.describe(alarm, EnglishStrings, TimeZone.of("Europe/Paris")),
        )
    }

    @Test
    fun an_occurrence_reads_in_the_devices_time() {
        val at = Instant.parse("2027-01-04T12:00:00Z")

        assertEquals("Mon 2027-01-04 07:00", RuleSummary.moment(at, portAuPrince, EnglishStrings))
        assertEquals("lun 2027-01-04 13:00", RuleSummary.moment(at, TimeZone.of("Europe/Paris"), FrenchStrings))
    }

    @Test
    fun only_three_days_in_a_row_or_more_become_a_range() {
        fun days(vararg days: ApiDayOfWeek) = RuleSummary.dayList(days.toSet(), EnglishStrings)

        assertEquals("Sat, Sun", days(ApiDayOfWeek.Saturday, ApiDayOfWeek.Sunday))
        assertEquals("Mon, Fri–Sun", days(ApiDayOfWeek.Sunday, ApiDayOfWeek.Friday, ApiDayOfWeek.Monday, ApiDayOfWeek.Saturday))
        assertEquals("Mon–Sun", days(*ApiDayOfWeek.entries.toTypedArray()))
    }
}
