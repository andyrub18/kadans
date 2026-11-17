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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

/** What tells a screen to load again, and what does not. */
@OptIn(ExperimentalCoroutinesApi::class)
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
        var budget = 0
        var todos = 0
        changes.reloadOn(backgroundScope, DataArea.Budget) { budget++ }
        changes.reloadOn(backgroundScope, DataArea.Todos) { todos++ }

        changes.changed(DataArea.Todos)
        runCurrent()
        hub.emit(RealtimeEvent.DataChanged(setOf("budget")))
        runCurrent()
        changes.cameBack()
        runCurrent()

        assertEquals(2, budget) // the hub's word, then back in front; not the todo change
        assertEquals(2, todos) // its own change, then back in front; not the budget's
    }

    @Test
    fun a_screen_told_the_moment_it_is_made_reloads() = runTest {
        // On a dispatcher that starts nothing by itself, and with nothing run in between: heard all the same.
        val hub = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 1)
        val changes = DataChanges(hub)
        var todos = 0
        var budget = 0
        changes.reloadOn(backgroundScope, DataArea.Todos) { todos++ }
        changes.reloadOn(backgroundScope, DataArea.Budget) { budget++ }

        changes.changed(DataArea.Todos) // a button here
        hub.emit(RealtimeEvent.DataChanged(setOf("budget"))) // the hub
        runCurrent()

        assertEquals(1, todos)
        assertEquals(1, budget)
    }

    @Test
    fun signals_arriving_while_a_reload_runs_make_one_more_not_one_each() = runTest {
        val changes = DataChanges(emptyFlow())
        val slow = CompletableDeferred<Unit>()
        var reloads = 0
        changes.reloadOn(backgroundScope, DataArea.Todos) {
            reloads++
            if (reloads == 1) slow.await()
        }

        changes.changed(DataArea.Todos)
        runCurrent() // the first reload began, and waits for its server
        repeat(5) { changes.changed(DataArea.Todos) }
        runCurrent()
        assertEquals(1, reloads)

        slow.complete(Unit)
        runCurrent()
        assertEquals(2, reloads)
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
