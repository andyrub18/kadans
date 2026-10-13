package app.kadans.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Windows and macOS: a click on a reminder's balloon reaches only the tray icon's action, taken as that click. */
class ReminderBalloonTests {
    private val t0 = Instant.parse("2027-01-04T13:45:00Z")

    @Test
    fun an_open_just_after_a_reminders_balloon_opens_its_todo_once() {
        ReminderBalloon.shown("kadans://todos/a", t0)

        assertEquals("kadans://todos/a", ReminderBalloon.takeClick(t0 + 20.seconds))
        assertNull(ReminderBalloon.takeClick(t0 + 30.seconds), "the next open is just the window")
    }

    @Test
    fun later_an_open_is_just_the_window() {
        ReminderBalloon.shown("kadans://todos/a", t0)

        assertNull(ReminderBalloon.takeClick(t0 + 2.minutes))
    }
}
