package app.kadans.realtime

import app.kadans.api.model.NotificationResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class CatchUpTests {
    private val dropped = Instant.parse("2027-01-04T14:00:00Z")

    private fun notification(id: String, at: Instant) =
        NotificationResponse(id = id, kind = "occurrence.due", title = "Water", body = "Starts at 09:15", createdAt = at)

    @Test
    fun what_arrived_during_the_gap_is_delivered_oldest_first() {
        val unread = listOf(
            notification("during-late", dropped + 40.seconds),
            notification("during-early", dropped + 5.seconds),
            notification("long-before", dropped - 3.hours()),
        )

        assertEquals(listOf("during-early", "during-late"), KadansRealtime.missedSince(unread, dropped, emptySet()).map { it.id })
    }

    @Test
    fun a_device_clock_ahead_of_the_server_still_catches_the_gap() {
        // The device's clock runs a minute fast: the server stamped the reminder "before" the drop.
        val unread = listOf(notification("in-the-gap", dropped - 1.minutes))

        assertEquals(listOf("in-the-gap"), KadansRealtime.missedSince(unread, dropped, emptySet()).map { it.id })
    }

    @Test
    fun one_already_shown_live_is_not_shown_again() {
        val unread = listOf(notification("live-before-the-drop", dropped - 10.seconds), notification("missed", dropped + 10.seconds))

        assertEquals(listOf("missed"), KadansRealtime.missedSince(unread, dropped, setOf("live-before-the-drop")).map { it.id })
    }

    private fun Int.hours() = (this * 60).minutes
}
