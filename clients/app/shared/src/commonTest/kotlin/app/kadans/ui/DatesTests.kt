package app.kadans.ui

import app.kadans.api.model.ClockFormat
import app.kadans.i18n.CreoleStrings
import app.kadans.i18n.EnglishStrings
import app.kadans.i18n.FrenchStrings
import app.kadans.i18n.Language
import app.kadans.ui.pomodoro.SessionEnd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

class DatesTests {
    private val today = LocalDate(2026, 11, 4) // a Wednesday
    private val en = Dates(EnglishStrings.dates, ClockFormat.TwentyFourHour)
    private val fr = Dates(FrenchStrings.dates, ClockFormat.TwentyFourHour)
    private val ht = Dates(CreoleStrings.dates, ClockFormat.TwentyFourHour)
    private val en12 = Dates(EnglishStrings.dates, ClockFormat.TwelveHour)

    @Test
    fun the_days_around_today_have_their_names() {
        assertEquals("Today", en.day(today, today))
        assertEquals("Tomorrow", en.shortDay(LocalDate(2026, 11, 5), today))
        assertEquals("Yesterday", en.shortDay(LocalDate(2026, 11, 3), today))
        assertEquals("Aujourd'hui", fr.day(today, today))
        assertEquals("Demain", fr.day(LocalDate(2026, 11, 5), today))
        assertEquals("Jodi a", ht.day(today, today))
        assertEquals("Yè", ht.shortDay(LocalDate(2026, 11, 3), today))
    }

    @Test
    fun other_days_are_written_out_in_each_language() {
        val thursday = LocalDate(2026, 11, 12)
        assertEquals("Thursday, November 12", en.day(thursday, today))
        assertEquals("Thu, Nov 12", en.shortDay(thursday, today))
        assertEquals("Jeudi 12 novembre", fr.day(thursday, today))
        assertEquals("Jeu. 12 nov.", fr.shortDay(thursday, today))
        assertEquals("Jedi 12 novanm", ht.day(thursday, today))
        assertEquals("Jed 12 nov", ht.shortDay(thursday, today))
        // The day named even when it is today: a calendar's heading, a form's field.
        assertEquals("Wednesday, November 4", en.long(today, today))
        assertEquals("Mer. 4 nov.", fr.short(today, today))
    }

    @Test
    fun the_year_shows_only_when_it_is_not_this_one() {
        val nextYear = LocalDate(2027, 1, 4)
        assertEquals("Monday, January 4, 2027", en.day(nextYear, today))
        assertEquals("Mon, Jan 4, 2027", en.shortDay(nextYear, today))
        assertEquals("Lundi 4 janvier 2027", fr.day(nextYear, today))
        assertEquals("Len 4 jan 2027", ht.shortDay(nextYear, today))
        // Inside a sentence ("until …"), a date always says its year.
        assertEquals("March 31, 2027", en.date(LocalDate(2027, 3, 31)))
        assertEquals("31 mars 2027", fr.date(LocalDate(2027, 3, 31)))
    }

    @Test
    fun the_first_of_the_month_is_written_as_each_language_writes_it() {
        val first = LocalDate(2026, 12, 1)
        assertEquals("Tuesday, December 1", en.day(first, today))
        assertEquals("Mardi 1er décembre", fr.day(first, today))
        assertEquals("Madi 1ye desanm", ht.day(first, today))
    }

    @Test
    fun times_read_on_the_accounts_clock() {
        assertEquals("21:00", en.time(LocalTime(21, 0)))
        assertEquals("09:05", fr.time(LocalTime(9, 5)))
        assertEquals("9:00 PM", en12.time(LocalTime(21, 0)))
        assertEquals("9:05 AM", en12.time(LocalTime(9, 5)))
        assertEquals("12:30 AM", en12.time(LocalTime(0, 30)))
        assertEquals("12:00 PM", en12.time(LocalTime(12, 0)))
        // AM/PM in every language, as in Haiti.
        assertEquals("9:00 PM", Dates(CreoleStrings.dates, ClockFormat.TwelveHour).time(LocalTime(21, 0)))
        assertEquals("9:00 PM", Dates(FrenchStrings.dates, ClockFormat.TwelveHour).time(LocalTime(21, 0)))
    }

    @Test
    fun a_moment_is_its_day_and_its_time_on_the_devices_wall_clock() {
        val at = Instant.parse("2026-11-05T02:15:00Z") // 21:15 on the 4th in Port-au-Prince, the 5th in UTC
        val portAuPrince = TimeZone.of("America/Port-au-Prince")
        assertEquals("Today · 21:15", en.moment(at, portAuPrince, today))
        assertEquals("Today · 9:15 PM", en12.moment(at, portAuPrince, today))
        assertEquals("Demain · 02:15", fr.moment(at, TimeZone.UTC, today))
    }

    @Test
    fun every_language_has_its_seven_days_and_twelve_months() {
        Language.entries.map { it.catalog.dates }.forEach { d ->
            assertEquals(7, d.weekdays.size)
            assertEquals(7, d.weekdaysShort.size)
            assertEquals(12, d.months.size)
            assertEquals(12, d.monthsShort.size)
            listOf(d.dayPattern, d.dayYearPattern, d.shortPattern, d.shortYearPattern).forEach {
                assertTrue("{wd}" in it && "{d}" in it && "{m}" in it, it)
            }
            assertTrue("{y}" in d.dayYearPattern && "{y}" in d.shortYearPattern && "{y}" in d.datePattern)
        }
    }

    @Test
    fun a_sessions_end_reads_on_the_accounts_clock() {
        val nineAm = Instant.parse("2027-01-04T14:00:00Z") // 09:00 in Port-au-Prince
        val portAuPrince = TimeZone.of("America/Port-au-Prince")
        assertEquals(
            "Ends at 9:00 PM",
            SessionEnd.label(nineAm + 12.hours, nineAm, portAuPrince, EnglishStrings.pomodoro, clockFormat = ClockFormat.TwelveHour),
        )
        assertEquals(
            "Ends tomorrow at 2:00 AM",
            SessionEnd.label(nineAm + 17.hours, nineAm, portAuPrince, EnglishStrings.pomodoro, clockFormat = ClockFormat.TwelveHour),
        )
    }
}
