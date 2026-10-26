package app.kadans.sync

import app.kadans.api.model.UpcomingReminder
import app.kadans.realtime.KadansRealtime
import app.kadans.realtime.RealtimeEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

/** What tells a screen to load again, and what does not. */
class DataChangesTests {
    @Test
    fun the_servers_data_changed_names_its_areas() {
        val payload = Json.parseToJsonElement("""{"areas":["budget","todos","later"]}""")
        assertEquals(setOf("budget", "todos", "later"), KadansRealtime.areasOf(payload))
        assertEquals(emptySet(), KadansRealtime.areasOf(Json.parseToJsonElement("{}")))

        // An area this app does not know yet (a newer server) is no reason to reload anything.
        assertEquals(setOf(DataArea.Budget, DataArea.Todos), DataChanges.areasOf(RealtimeEvent.DataChanged(setOf("budget", "todos", "later"))))
        assertNull(DataChanges.areasOf(RealtimeEvent.DataChanged(setOf("later"))))
    }

    @Test
    fun a_reconnect_reloads_everything_a_session_the_todos_a_reminder_window_nothing() {
        assertEquals(DataArea.entries.toSet(), DataChanges.areasOf(RealtimeEvent.Reconnected))
        assertNull(DataChanges.areasOf(RealtimeEvent.RemindersChanged))
    }

    @Test
    fun each_screen_hears_its_own_area_and_coming_back() = runTest {
        val hub = MutableSharedFlow<RealtimeEvent>()
        val changes = DataChanges(hub)
        val budget = async { changes.of(DataArea.Budget).take(2).toList() }
        val todos = async { changes.of(DataArea.Todos).first() }
        runCurrent()

        changes.changed(DataArea.Todos)
        hub.emit(RealtimeEvent.DataChanged(setOf("budget")))
        changes.cameBack()

        assertEquals(2, budget.await().size) // the hub's word, then back in front; not the todo change
        todos.await()
    }

    @Test
    fun only_the_reminder_before_the_start_is_a_heads_up() {
        fun reminder(notifyAt: String, snoozedFrom: String? = null) = UpcomingReminder(
            occurrenceId = "o1", todoId = "t1", title = "Gym", body = "",
            notifyAt = Instant.parse(notifyAt), startsAt = Instant.parse("2026-10-26T22:00:00Z"),
            snoozedFrom = snoozedFrom?.let(Instant::parse),
        )
        assertTrue(reminder("2026-10-26T21:55:00Z").isHeadsUp)
        assertFalse(reminder("2026-10-26T22:00:00Z").isHeadsUp, "starts now: the buttons are there")
        assertFalse(reminder("2026-10-26T22:10:00Z", snoozedFrom = "2026-10-26T22:00:00Z").isHeadsUp, "rung again after In 10 min")
    }
}
