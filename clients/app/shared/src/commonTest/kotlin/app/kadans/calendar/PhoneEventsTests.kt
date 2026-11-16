package app.kadans.calendar

import app.kadans.api.model.ClockFormat
import app.kadans.i18n.EnglishStrings
import app.kadans.ui.Dates
import app.kadans.ui.calendar.DayItem
import app.kadans.ui.calendar.dayItems
import app.kadans.ui.calendar.daysWithItems
import app.kadans.ui.calendar.eventHours
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.LocalDate

/** Which days an event of the phone's calendar is on, and how a day's list reads with todos and events together. */
class PhoneEventsTests {
    private val monday = LocalDate(2026, 11, 16)
    private val tuesday = LocalDate(2026, 11, 17)
    private val wednesday = LocalDate(2026, 11, 18)
    private val dates = Dates(EnglishStrings.dates, ClockFormat.TwentyFourHour)

    private fun List<DayItem>.titles() = map {
        when (it) {
            is DayItem.Todo -> "todo: ${it.occurrence.todoTitle}"
            is DayItem.Event -> "event: ${it.event.title}"
        }
    }

    @Test
    fun an_event_is_on_the_days_it_touches() {
        // 10:00 to 11:00 on Monday.
        assertEquals(monday..monday, event(1, "Meeting", MONDAY_NINE + 1.hours).days(HAITI))
        // Ends at midnight: it does not touch Tuesday.
        assertEquals(monday..monday, event(2, "Late", MONDAY_NINE + 13.hours, 2.hours).days(HAITI))
        // A night shift: 22:00 on Monday to 06:00 on Tuesday.
        assertEquals(monday..tuesday, event(3, "Night shift", MONDAY_NINE + 13.hours, 8.hours).days(HAITI))
        // A whole day is the day the calendar says, wherever the phone is: no time zone moves a holiday to the day before.
        assertEquals(tuesday..tuesday, wholeDay(4, "Holiday", tuesday).days(HAITI))
        assertEquals(monday..wednesday, wholeDay(5, "Conference", monday, wednesday).days(HAITI))
        // Without a length: the day it is on.
        assertEquals(monday..monday, event(6, "Reminder", MONDAY_NINE, 0.minutes).days(HAITI))
    }

    @Test
    fun a_days_list_has_whole_day_events_first_then_todos_and_events_by_the_hour() {
        val items = dayItems(
            monday,
            occurrences = listOf(todo("Morning run", MONDAY_NINE), todo("Call the bank", MONDAY_NINE + 5.hours)),
            events = listOf(
                event(1, "Team meeting", MONDAY_NINE + 1.hours),
                wholeDay(2, "Holiday", monday),
                event(3, "Stand-up", MONDAY_NINE, 15.minutes),
            ),
            zone = HAITI,
        )
        // At 09:00 both a todo and an event: the todo first, it is the person's own thing to do.
        assertEquals(
            listOf("event: Holiday", "todo: Morning run", "event: Stand-up", "event: Team meeting", "todo: Call the bank"),
            items.titles(),
        )
    }

    @Test
    fun the_next_days_are_those_with_a_todo_or_an_event() {
        val days = daysWithItems(
            occurrences = listOf(todo("Morning run", MONDAY_NINE), todo("Dentist", MONDAY_NINE + 2.days)),
            events = listOf(
                event(1, "Night shift", MONDAY_NINE + 13.hours, 8.hours),
                wholeDay(2, "Conference", tuesday, LocalDate(2026, 11, 30)),
            ),
            zone = HAITI,
            from = monday,
            to = wednesday,
        )
        assertEquals(listOf(monday, tuesday, wednesday), days.map { it.first })
        assertEquals(listOf("todo: Morning run", "event: Night shift"), days[0].second.titles())
        // Tuesday has no todo: it is there for its events. The shift that began on Monday counts from Tuesday's start.
        assertEquals(listOf("event: Conference", "event: Night shift"), days[1].second.titles())
        // The conference goes on past the days asked for: listed on those only.
        assertEquals(listOf("event: Conference", "todo: Dentist"), days[2].second.titles())
    }

    @Test
    fun an_events_hours_are_written_for_the_day_they_are_read_on() {
        val shift = event(1, "Night shift", MONDAY_NINE + 13.hours, 8.hours) // Monday 22:00 to Tuesday 06:00
        assertEquals("10:00 – 11:00", eventHours(event(2, "Meeting", MONDAY_NINE + 1.hours), monday, HAITI, dates, "All day"))
        assertEquals("22:00 – …", eventHours(shift, monday, HAITI, dates, "All day"))
        assertEquals("… – 06:00", eventHours(shift, tuesday, HAITI, dates, "All day"))
        assertEquals("All day", eventHours(wholeDay(3, "Holiday", monday), monday, HAITI, dates, "All day"))
        // Three days long, read on the middle one: the whole of that day.
        assertEquals("All day", eventHours(event(4, "Retreat", MONDAY_NINE, 60.hours), tuesday, HAITI, dates, "All day"))
        assertEquals("09:00", eventHours(event(5, "Reminder", MONDAY_NINE, 0.minutes), monday, HAITI, dates, "All day"))
        assertEquals("9:00 AM – 10:00 AM", eventHours(event(6, "Meeting", MONDAY_NINE), monday, HAITI, Dates(EnglishStrings.dates, ClockFormat.TwelveHour), "All day"))
    }

    @Test
    fun a_whole_day_event_is_never_taken_for_a_todo() {
        // A birthday titled like a todo that happens to be at midnight UTC: still the calendar's.
        val birthday = wholeDay(1, "Morning run", monday)
        assertEquals(listOf(birthday), PhoneEvents.withoutKadans(listOf(birthday), listOf(todo("Morning run", birthday.start))))
    }
}
