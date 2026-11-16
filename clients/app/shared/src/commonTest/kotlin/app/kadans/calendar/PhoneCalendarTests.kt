package app.kadans.calendar

import app.kadans.api.model.OccurrenceStatus
import app.kadans.api.model.TodoOccurrenceResponse
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

internal val HAITI = TimeZone.of("America/Port-au-Prince") // UTC−5 in November

/** Monday 16 November 2026, 09:00 in Port-au-Prince. */
internal val MONDAY_NINE: Instant = Instant.parse("2026-11-16T14:00:00Z")

internal fun event(id: Long, title: String, start: Instant, length: kotlin.time.Duration = 1.hours, calendar: String = "Work") =
    PhoneEvent(id, title, start, start + length, calendar = calendar)

internal fun wholeDay(id: Long, title: String, first: LocalDate, last: LocalDate = first) =
    PhoneEvent(
        id, title,
        start = Instant.parse("${first}T00:00:00Z"), end = Instant.parse("${last}T00:00:00Z") + 1.days,
        allDay = true, firstDay = first, lastDay = last, calendar = "Holidays",
    )

internal fun todo(title: String, at: Instant) =
    TodoOccurrenceResponse(id = "o-$title", todoId = "t-$title", todoTitle = title, scheduledAt = at, originalScheduledAt = at, status = OccurrenceStatus.Pending)

/** The phone's calendar beside the todos (ARCHITECTURE → "The phone's calendar"): what is read, and when nothing is. */
class PhoneCalendarTests {
    /** The phone: whether Kadans may read its calendar, and what is in it. */
    private class Phone(var access: CalendarAccess = CalendarAccess.Granted) : PhoneCalendarSource {
        var events = listOf<PhoneEvent>()
        var reads = 0
        var asked = 0
        var broken = false
        val opened = mutableListOf<PhoneEvent>()

        override fun access() = access

        override fun requestAccess() {
            asked++
        }

        override suspend fun events(from: Instant, to: Instant): List<PhoneEvent> {
            reads++
            check(!broken) { "the calendar cannot be read" }
            return events.filter { it.end > from && it.start < to }
        }

        override fun open(event: PhoneEvent) {
            opened += event
        }
    }

    private val week = MONDAY_NINE to MONDAY_NINE + 7.days

    @Test
    fun off_by_default_the_phones_calendar_is_not_even_read() = runTest {
        val phone = Phone().apply { events = listOf(event(1, "Team meeting", MONDAY_NINE + 1.hours)) }
        val calendar = PhoneCalendar(phone, PhoneCalendarStore(MapSettings()))

        assertEquals(PhoneCalendarState.Off, calendar.state.value)
        assertEquals(emptyList(), calendar.events(week.first, week.second))
        assertEquals(0, phone.reads)
        assertTrue(calendar.offer.value, "the Calendar tab offers it, once")
    }

    @Test
    fun turning_it_on_asks_for_the_permission_and_shows_events_once_it_is_given() = runTest {
        val phone = Phone(CalendarAccess.Denied).apply { events = listOf(event(1, "Team meeting", MONDAY_NINE + 1.hours)) }
        val settings = MapSettings()
        val calendar = PhoneCalendar(phone, PhoneCalendarStore(settings))

        calendar.turnOn()
        assertEquals(1, phone.asked)
        assertEquals(PhoneCalendarState.NeedsPermission, calendar.state.value)
        assertEquals(emptyList(), calendar.events(week.first, week.second))
        assertEquals(0, phone.reads, "without the permission nothing is read")
        assertFalse(calendar.offer.value)

        // Back from Android's question, with a yes.
        phone.access = CalendarAccess.Granted
        calendar.cameBack()
        assertEquals(PhoneCalendarState.On, calendar.state.value)
        assertEquals(listOf("Team meeting"), calendar.events(week.first, week.second).map { it.title })

        // The switch is the phone's: the next start has it on.
        assertEquals(PhoneCalendarState.On, PhoneCalendar(phone, PhoneCalendarStore(settings)).state.value)

        // A no, and then the permission taken back in the system's settings: the button asks again.
        phone.access = CalendarAccess.Denied
        calendar.cameBack()
        assertEquals(PhoneCalendarState.NeedsPermission, calendar.state.value)
        calendar.allow()
        assertEquals(2, phone.asked)
    }

    @Test
    fun turning_it_off_shows_nothing_more_and_declining_the_offer_ends_it() = runTest {
        val phone = Phone().apply { events = listOf(event(1, "Team meeting", MONDAY_NINE + 1.hours)) }
        val calendar = PhoneCalendar(phone, PhoneCalendarStore(MapSettings()))

        calendar.offerDeclined()
        assertFalse(calendar.offer.value)
        assertEquals(PhoneCalendarState.Off, calendar.state.value)

        calendar.turnOn()
        assertEquals(0, phone.asked, "already allowed: no question")
        assertEquals(1, calendar.events(week.first, week.second).size)
        calendar.turnOff()
        assertEquals(emptyList(), calendar.events(week.first, week.second))
        assertFalse(calendar.offer.value, "not offered to someone who used the switch")
    }

    @Test
    fun a_calendar_that_cannot_be_read_never_fails_a_screen_of_todos() = runTest {
        val phone = Phone().apply { broken = true }
        val calendar = PhoneCalendar(phone, PhoneCalendarStore(MapSettings())).apply { turnOn() }

        assertEquals(emptyList(), calendar.events(week.first, week.second))
        assertEquals(1, phone.reads)
    }

    @Test
    fun a_device_without_a_calendar_offers_nothing() = runTest {
        val calendar = PhoneCalendar(NoPhoneCalendar, PhoneCalendarStore(MapSettings()))

        assertEquals(PhoneCalendarState.Unsupported, calendar.state.value)
        assertFalse(calendar.offer.value)
        calendar.turnOn()
        assertEquals(PhoneCalendarState.Unsupported, calendar.state.value)
        assertEquals(emptyList(), calendar.events(week.first, week.second))
    }

    @Test
    fun a_todo_that_came_back_through_the_calendar_feed_is_not_shown_twice() = runTest {
        // The person gave Google Calendar their Kadans feed: each todo is in the phone's calendar too.
        val run = MONDAY_NINE + 2.hours
        val phone = Phone().apply {
            events = listOf(
                event(1, "Morning run", run, 30.minutes, calendar = "Kadans"),
                event(2, "✓ Call the bank", MONDAY_NINE + 5.hours, 30.minutes, calendar = "Kadans"),
                event(3, "Team meeting", run),
                event(4, "Morning run", run + 1.days, calendar = "Club"),
            )
        }
        val calendar = PhoneCalendar(phone, PhoneCalendarStore(MapSettings())).apply { turnOn() }
        val todos = listOf(todo("Morning run", run), todo("Call the bank", MONDAY_NINE + 5.hours))

        // Same title at the same minute is the todo itself, done or not; a meeting at that minute is not, nor is a
        // namesake on another day.
        assertEquals(listOf(3L, 4L), calendar.events(week.first, week.second, todos).map { it.id })
    }

    @Test
    fun the_state_tells_the_screens_when_what_they_show_changes() = runTest {
        val phone = Phone(CalendarAccess.Denied)
        val calendar = PhoneCalendar(phone, PhoneCalendarStore(MapSettings()))
        var changes = 0
        val listening = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { calendar.changes.collect { changes++ } }

        calendar.cameBack()
        testScheduler.runCurrent()
        assertEquals(0, changes, "nothing changed")
        calendar.turnOn()
        testScheduler.runCurrent()
        assertEquals(1, changes)
        phone.access = CalendarAccess.Granted
        calendar.cameBack()
        testScheduler.runCurrent()
        assertEquals(2, changes)
        calendar.turnOff()
        testScheduler.runCurrent()
        assertEquals(3, changes)
        listening.cancel()
    }
}
