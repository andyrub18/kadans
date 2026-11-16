package app.kadans.calendar

import app.kadans.api.model.TodoOccurrenceResponse
import com.russhwolf.settings.Settings
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * An event of the phone's own calendar, as Kadans shows it beside the todos (ARCHITECTURE → "The phone's calendar").
 * It is read from the phone when a screen shows it, and goes no further: never kept by Kadans, never sent to the
 * server, never written to a log.
 */
data class PhoneEvent(
    /** The system's id for the event; with [start] it names one occurrence of a repeating one. */
    val id: Long,
    val title: String,
    val start: Instant,
    val end: Instant,
    val allDay: Boolean = false,
    /** All-day: the first and the last day it covers, as the calendar holds them: no time zone moves a birthday. */
    val firstDay: LocalDate? = null,
    val lastDay: LocalDate? = null,
    /** The calendar it is in, as the phone's calendar app names it ("Work"); may be empty. */
    val calendar: String = "",
    /** That calendar's colour in the phone's calendar app (ARGB), when it has one. */
    val color: Int? = null,
) {
    /** The days it covers in [zone]: one for most; a conference's three, a night shift's two. */
    fun days(zone: TimeZone): ClosedRange<LocalDate> {
        if (allDay && firstDay != null) return firstDay..(lastDay ?: firstDay).coerceAtLeast(firstDay)
        val first = start.toLocalDateTime(zone).date
        // An event that ends at midnight does not touch the day that begins there.
        val last = (if (end > start) end - 1.milliseconds else start).toLocalDateTime(zone).date
        return first..last.coerceAtLeast(first)
    }
}

/** What is shown of the phone's events, and where: pure, so that it can be said in tests. */
object PhoneEvents {
    /** The mark the calendar feed puts before a todo that is done (the server's `CalendarFeedEvents`). */
    const val DONE_MARK = "✓ "

    /**
     * [events] without Kadans' own todos come back: someone who gave their calendar app the calendar feed has every todo
     * in the phone's calendar too, and would see it twice. An event is one of them when it starts at the minute a todo
     * of [occurrences] does and bears its title, with or without the feed's mark for "done". All-day events never are:
     * a todo has an hour.
     */
    fun withoutKadans(events: List<PhoneEvent>, occurrences: List<TodoOccurrenceResponse>): List<PhoneEvent> {
        if (occurrences.isEmpty()) return events
        val ours = occurrences.mapTo(HashSet()) { it.scheduledAt.epochSeconds / 60 to it.todoTitle.trim() }
        return events.filterNot { event -> !event.allDay && (event.start.epochSeconds / 60 to event.title.removePrefix(DONE_MARK).trim()) in ours }
    }

    /** The events that touch [day], as a day's list has them: those of the whole day first, then by their start. */
    fun on(day: LocalDate, events: List<PhoneEvent>, zone: TimeZone): List<PhoneEvent> =
        events.filter { day in it.days(zone) }.sortedWith(ORDER)

    /** Each day from [from] to [to] that has events, with them. */
    fun byDay(events: List<PhoneEvent>, from: LocalDate, to: LocalDate, zone: TimeZone): Map<LocalDate, List<PhoneEvent>> {
        val days = LinkedHashMap<LocalDate, MutableList<PhoneEvent>>()
        for (event in events.sortedWith(ORDER)) {
            val covered = event.days(zone)
            var day = maxOf(covered.start, from)
            val last = minOf(covered.endInclusive, to)
            while (day <= last) {
                days.getOrPut(day) { mutableListOf() }.add(event)
                day = day.plus(1, DateTimeUnit.DAY)
            }
        }
        return days.entries.sortedBy { it.key }.associate { it.key to it.value }
    }

    private val ORDER = compareByDescending<PhoneEvent> { it.allDay }.thenBy { it.start }.thenBy { it.title }
}

/** Whether Kadans may read the phone's calendar. */
enum class CalendarAccess {
    Granted,

    /** The person has not said yes (not asked yet, or refused). */
    Denied,

    /** No calendar to read here: a computer, and the iPhone until its app has it. */
    Unsupported,
}

/**
 * The phone's side of its calendar (ARCHITECTURE → "The phone's calendar"). [PhoneCalendar] decides whether events are
 * shown; this only talks to the system. Android: the calendar provider every calendar app on the phone writes to.
 */
interface PhoneCalendarSource {
    fun access(): CalendarAccess

    /**
     * Asks the person, with the system's own question; when the system no longer asks (refused twice), opens Kadans'
     * page in the system's settings, where the permission is. From a screen of the app only.
     */
    fun requestAccess()

    /**
     * The events between [from] and [to], an occurrence of a repeating one counted each time: those of the calendars
     * the person shows in their calendar app, without the ones they declined and the cancelled ones.
     */
    suspend fun events(from: Instant, to: Instant): List<PhoneEvent>

    /** Opens [event] in the phone's calendar app. */
    fun open(event: PhoneEvent)
}

/** A device without a calendar Kadans can read. */
object NoPhoneCalendar : PhoneCalendarSource {
    override fun access() = CalendarAccess.Unsupported

    override fun requestAccess() = Unit

    override suspend fun events(from: Instant, to: Instant): List<PhoneEvent> = emptyList()

    override fun open(event: PhoneEvent) = Unit
}

/** This platform's calendar (Android: `AndroidPhoneCalendar`). */
expect fun platformPhoneCalendar(): PhoneCalendarSource

/** What Settings shows for "My phone's calendar". */
enum class PhoneCalendarState { Unsupported, Off, On, NeedsPermission }

/** The switch, kept in the app's settings: this device's own, like its language. Nothing of the calendar is kept. */
class PhoneCalendarStore(private val settings: Settings) {
    var wanted: Boolean
        get() = settings.getBoolean(WANTED, false)
        set(value) = settings.putBoolean(WANTED, value)

    /** The Calendar tab's one-time offer was answered, or the switch used: it is not made again. */
    var offered: Boolean
        get() = settings.getBoolean(OFFERED, false)
        set(value) = settings.putBoolean(OFFERED, value)

    private companion object {
        const val WANTED = "kadans.phoneCalendar.wanted"
        const val OFFERED = "kadans.phoneCalendar.offered"
    }
}

/**
 * The phone's calendar beside the todos (ARCHITECTURE → "The phone's calendar"): with its switch on and Android's
 * permission, Home and the Calendar tab show the phone's events among the todos, read-only. Off by default. The events
 * are read when a screen asks and handed to it: nothing is kept here, and nothing reaches the server.
 */
class PhoneCalendar(private val source: PhoneCalendarSource, private val store: PhoneCalendarStore) {
    private val _state = MutableStateFlow(stateNow())

    /** What Settings shows. */
    val state: StateFlow<PhoneCalendarState> = _state.asStateFlow()

    private val _offer = MutableStateFlow(offerNow())

    /** The Calendar tab offers the switch, once, on a phone that can and has it off. */
    val offer: StateFlow<Boolean> = _offer.asStateFlow()

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** What the screens show of the calendar may have changed (the switch, the permission): they load again. */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    /** The switch, on. Without the permission the person is asked for it; [cameBack] sees the answer. */
    fun turnOn() {
        store.wanted = true
        store.offered = true
        if (source.access() == CalendarAccess.Denied) source.requestAccess()
        publish()
    }

    fun turnOff() {
        store.wanted = false
        store.offered = true
        publish()
    }

    /** The Calendar tab's offer was declined: it is not made again (Settings keeps the switch). */
    fun offerDeclined() {
        store.offered = true
        publish()
    }

    /** Asks for the permission again, or opens the system's settings when Android no longer asks. */
    fun allow() = source.requestAccess()

    /** The app is back in front, maybe from the system's question or its settings: what is allowed is read again. */
    fun cameBack() = publish()

    /**
     * The phone's events between [from] and [to], for a screen that shows [kadans] (the todos of the same days: the
     * ones that came back through the calendar feed are left out). Empty unless the switch is on and Android allows
     * it, and when the phone's calendar cannot be read: a screen of todos never fails for it.
     */
    suspend fun events(from: Instant, to: Instant, kadans: List<TodoOccurrenceResponse> = emptyList()): List<PhoneEvent> {
        if (stateNow() != PhoneCalendarState.On) return emptyList()
        return try {
            PhoneEvents.withoutKadans(source.events(from, to), kadans)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Opens [event] in the phone's calendar app. */
    fun open(event: PhoneEvent) = source.open(event)

    private fun publish() {
        val now = stateNow()
        val changed = _state.value != now
        _state.value = now
        _offer.value = offerNow()
        if (changed) _changes.tryEmit(Unit)
    }

    private fun stateNow(): PhoneCalendarState {
        val access = source.access()
        return when {
            access == CalendarAccess.Unsupported -> PhoneCalendarState.Unsupported
            !store.wanted -> PhoneCalendarState.Off
            access == CalendarAccess.Granted -> PhoneCalendarState.On
            else -> PhoneCalendarState.NeedsPermission
        }
    }

    private fun offerNow(): Boolean = source.access() != CalendarAccess.Unsupported && !store.wanted && !store.offered
}
