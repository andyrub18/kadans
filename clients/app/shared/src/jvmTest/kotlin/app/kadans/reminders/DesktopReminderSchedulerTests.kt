package app.kadans.reminders

import app.kadans.api.model.UpcomingReminder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The desktop app's timer: the next reminder only, the latest schedule only, and on time after the computer slept. */
class DesktopReminderSchedulerTests {
    private val t0 = Instant.parse("2026-10-09T12:00:00Z")

    private fun reminder(id: String, at: Instant) = UpcomingReminder(id, "todo-$id", id, "", at, at + 15.minutes)

    private class Clock(val scope: TestScope, val start: Instant) {
        /** How far the wall clock ran ahead of the coroutines' own time: a computer that slept. */
        var slept = Duration.ZERO
        fun now() = start + scope.testScheduler.currentTime.milliseconds + slept
    }

    private fun TestScope.timer(clock: Clock, onDue: () -> Unit) =
        DesktopReminderScheduler(onDue = { onDue() }, now = clock::now, scope = backgroundScope)

    @Test
    fun it_rings_at_the_soonest_reminder() = runTest {
        var rang = 0
        val scheduler = timer(Clock(this, t0)) { rang++ }

        scheduler.schedule(listOf(reminder("later", t0 + 10.minutes), reminder("soon", t0 + 90.seconds)))
        advanceTimeBy(89.seconds); runCurrent()
        assertEquals(0, rang)
        advanceTimeBy(2.seconds); runCurrent()
        assertEquals(1, rang)
    }

    @Test
    fun a_new_schedule_replaces_the_timer_and_an_empty_one_stops_it() = runTest {
        var rang = 0
        val scheduler = timer(Clock(this, t0)) { rang++ }

        scheduler.schedule(listOf(reminder("a", t0 + 1.minutes)))
        scheduler.schedule(listOf(reminder("b", t0 + 2.minutes)))
        advanceTimeBy(61.seconds); runCurrent()
        assertEquals(0, rang, "the first schedule's timer is gone")
        advanceTimeBy(60.seconds); runCurrent()
        assertEquals(1, rang)

        scheduler.schedule(listOf(reminder("c", t0 + 5.minutes)))
        scheduler.schedule(emptyList())
        advanceTimeBy(10.minutes); runCurrent()
        assertEquals(1, rang)
    }

    @Test
    fun after_the_computer_slept_it_rings_within_one_step() = runTest {
        var rang = 0
        val clock = Clock(this, t0)
        val scheduler = timer(clock) { rang++ }

        scheduler.schedule(listOf(reminder("a", t0 + 20.minutes)))
        advanceTimeBy(1.minutes); runCurrent()
        clock.slept = 30.minutes // the coroutines' clock stood still while the computer slept
        advanceTimeBy(DesktopReminderScheduler.STEP); runCurrent()
        assertEquals(1, rang)
    }
}
