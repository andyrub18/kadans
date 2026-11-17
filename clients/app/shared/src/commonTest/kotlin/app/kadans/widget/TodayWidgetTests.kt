package app.kadans.widget

import app.kadans.api.AuthTokens
import app.kadans.api.InMemoryTokenStore
import app.kadans.api.KadansApi
import app.kadans.billing.PurchaseOutcome
import app.kadans.billing.StoreBilling
import app.kadans.billing.StoreProduct
import app.kadans.billing.SubscriptionGate
import app.kadans.realtime.RealtimeEvent
import app.kadans.sync.DataArea
import app.kadans.sync.DataChanges
import com.russhwolf.settings.MapSettings
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone

/**
 * The home-screen widget's data (ARCHITECTURE → "The home-screen widget"): what is fetched and when, what stays through
 * a server that cannot be reached, and what a phone without a session or a subscription shows. In real time
 * (Dispatchers.Default) where a signal makes the fetch.
 */
class TodayWidgetTests {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val haiti = TimeZone.of("America/Port-au-Prince")
    private val morning = Instant.parse("2026-11-13T15:30:00Z") // Friday, 10:30 in Port-au-Prince

    /** The home screen: whether a widget is placed, and everything it was asked to show. */
    private class Screen(var placed: Boolean = true) : WidgetSurface {
        val shown = mutableListOf<Pair<List<String>?, WidgetGap?>>()

        /** The occurrences whose "Done" was tapped, as last handed over with what to draw. */
        var doneAt: Map<String, Instant> = emptyMap()

        override fun present() = placed

        override suspend fun show(entries: List<WidgetEntry>?, gap: WidgetGap?, doneAt: Map<String, Instant>) {
            shown += entries?.map { it.title } to gap
            this.doneAt = doneAt
        }

        var asked = 0

        override fun canAdd() = true

        override fun add() {
            asked++
        }
    }

    private class Store(override val isSupported: Boolean = true) : StoreBilling {
        override suspend fun product(productId: String): StoreProduct? = null
        override suspend fun purchase(productId: String, accountHash: String): PurchaseOutcome = PurchaseOutcome.Cancelled
        override suspend fun ownedPurchaseTokens(productId: String): List<String> = emptyList()
        override fun manageUrl(productId: String): String? = null
    }

    /** The server: the account's pending todos, whether it sells subscriptions and this account has one. */
    private inner class Server {
        var todos = listOf("Morning run" to "2026-11-13T14:00:00Z", "Call the bank" to "2026-11-13T23:00:00Z")
        var required = false
        var hasAccess = true
        var reachable = true

        /** What "complete" answers: 200, or a refusal (409 with its error code, 404, 503). */
        var completes: Pair<Int, String?> = 200 to null
        val asked = mutableListOf<String>()
        val tokens = InMemoryTokenStore(AuthTokens("a", "r"))

        val api: KadansApi = KadansApi.create(
            "http://test",
            tokenStore = tokens,
            engine = MockEngine { request ->
                check(reachable) { "offline" }
                asked += "${request.method.value} ${request.url.encodedPath}" +
                    (request.url.parameters["from"]?.let { " $it..${request.url.parameters["to"]}" } ?: "")
                when (request.url.encodedPath) {
                    "/occurrences" -> respond(
                        todos.mapIndexed { i, (title, time) ->
                            """{"id":"o$i","todoId":"t$i","todoTitle":"$title","scheduledAt":"$time","originalScheduledAt":"$time","status":"Pending"}"""
                        }.joinToString(",", "[", "]"),
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                    "/billing/subscription" -> respond("""{"required":$required,"hasAccess":$hasAccess}""", HttpStatusCode.OK, jsonHeaders)
                    "/occurrences/o0/complete", "/occurrences/o1/complete" -> {
                        val (status, code) = completes
                        if (status == 200) {
                            val index = request.url.encodedPath.removePrefix("/occurrences/o").removeSuffix("/complete").toInt()
                            todos = todos.filterIndexed { i, _ -> i != index } // done: no longer pending
                            respond("{}", HttpStatusCode.OK, jsonHeaders)
                        } else {
                            respond("""{"title":"Refused","status":$status,"errorCode":${code?.let { "\"$it\"" }}}""", HttpStatusCode.fromValue(status), jsonHeaders)
                        }
                    }
                    else -> respond("{}", HttpStatusCode.OK, jsonHeaders)
                }
            },
        )

        fun fetches() = asked.count { it.startsWith("GET /occurrences") }

        fun completions() = asked.filter { it.endsWith("/complete") }
    }

    /** The occurrences whose reminders were told to stay quiet on this device. */
    private val settled = mutableListOf<String>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun widget(
        server: Server,
        screen: Screen,
        settings: MapSettings = MapSettings(),
        phone: Boolean = true,
        clock: () -> Instant = { morning },
        events: MutableSharedFlow<RealtimeEvent> = MutableSharedFlow(),
        changes: DataChanges = DataChanges(events),
        listening: Boolean = false,
    ) = TodayWidget(
        server.api,
        TodayWidgetStore(settings),
        screen,
        SubscriptionGate(server.api, Store(), isPhone = { phone }),
        events,
        changes,
        settled = { settled += it },
        now = clock,
        zone = { haiti },
        // Most tests call the widget themselves and want nothing else to move: it listens to no signal (a scope
        // already over). The app has one of these per process; a test that makes a second one on the same settings
        // ("Android stopped the process") must not have the first still answering signals beside it.
        scope = if (listening) scope else CoroutineScope(Job().apply { cancel() }),
    )

    /** Waits for what a signal sets off on another thread; a read caught mid-write just asks again. */
    private suspend fun until(what: String, condition: () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(10_000) { while (!runCatching(condition).getOrDefault(false)) delay(10) }
    }

    @Test
    fun it_fetches_the_phones_today_and_tomorrow_and_shows_them() = runTest {
        val server = Server()
        val screen = Screen()

        widget(server, screen).refresh()

        // Friday and Saturday in Port-au-Prince, midnight to midnight there.
        assertEquals("GET /occurrences 2026-11-13T05:00:00Z..2026-11-15T05:00:00Z", server.asked.last())
        assertEquals(listOf("Morning run", "Call the bank") to null, screen.shown.last())
        scope.cancel()
    }

    @Test
    fun without_a_widget_on_the_home_screen_nothing_is_fetched() = runTest {
        val server = Server()
        val screen = Screen(placed = false)

        widget(server, screen).refresh()

        assertTrue(server.asked.isEmpty())
        assertTrue(screen.shown.isEmpty())
        scope.cancel()
    }

    @Test
    fun what_was_fetched_stays_when_the_server_cannot_be_reached() = runTest {
        val server = Server()
        val screen = Screen()
        val settings = MapSettings()
        widget(server, screen, settings).refresh()

        // The app's process is gone and the phone is offline: another start of the widget, on the same settings.
        server.reachable = false
        val later = widget(server, screen, settings)
        later.refresh()
        later.redraw()

        assertEquals(listOf("Morning run", "Call the bank") to null, screen.shown.last())
        scope.cancel()
    }

    @Test
    fun placed_while_offline_it_says_to_open_the_app() = runTest {
        val server = Server().apply { reachable = false }
        val screen = Screen()

        widget(server, screen).refresh()

        assertEquals(null to WidgetGap.NotLoaded, screen.shown.last())
        scope.cancel()
    }

    @Test
    fun signed_out_it_says_so_and_asks_the_server_nothing() = runTest {
        val server = Server()
        val screen = Screen()
        server.tokens.save(null)

        widget(server, screen).refresh()

        assertTrue(server.asked.isEmpty())
        assertEquals(null to WidgetGap.SignedOut, screen.shown.last())
        scope.cancel()
    }

    @Test
    fun signing_out_leaves_nothing_of_the_account_on_the_phone() = runTest {
        val server = Server()
        val screen = Screen()
        val settings = MapSettings()
        val widget = widget(server, screen, settings)
        widget.refresh()

        server.tokens.save(null)
        widget.forget()

        assertEquals(null to WidgetGap.SignedOut, screen.shown.last())
        assertNull(TodayWidgetStore(settings).entries())
        assertTrue(settings.keys.none { it.startsWith("kadans.widget") })
        scope.cancel()
    }

    @Test
    fun a_phone_without_a_subscription_shows_the_widget_locked_as_the_app_is() = runTest {
        val server = Server().apply { required = true; hasAccess = false }
        val screen = Screen()
        val widget = widget(server, screen)

        widget.refresh()

        assertEquals(0, server.fetches()) // the todos are not even asked for
        assertEquals(null to WidgetGap.Locked, screen.shown.last())

        // Subscribed: the next fetch shows the day.
        server.hasAccess = true
        widget.refresh()
        assertEquals(listOf("Morning run", "Call the bank") to null, screen.shown.last())
        scope.cancel()
    }

    @Test
    fun a_subscription_that_ends_locks_a_widget_that_showed_the_day() = runTest {
        val server = Server().apply { required = true }
        val screen = Screen()
        val widget = widget(server, screen)
        widget.refresh()
        assertEquals(listOf("Morning run", "Call the bank") to null, screen.shown.last())

        server.hasAccess = false
        widget.refresh()

        assertEquals(WidgetGap.Locked, screen.shown.last().second)
        scope.cancel()
    }

    @Test
    fun a_todo_changed_on_this_phone_is_fetched_again_without_being_asked() = runTest {
        val server = Server()
        val screen = Screen()
        val widget = widget(server, screen, listening = true)
        widget.refresh()

        server.api.todos.completeOccurrence("o0") // the run is marked done in the app

        until("fetched again") { screen.shown.last() == (listOf("Call the bank") to null) }
        scope.cancel()
    }

    @Test
    fun what_is_signalled_the_moment_the_widget_is_made_is_heard() = runTest {
        // With nothing before it, time after time: a listener that subscribes a moment late hears none of these.
        val server = Server()
        repeat(30) { round ->
            val screen = Screen()
            val events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 4)
            val changes = DataChanges(events)
            widget(server, screen, events = events, changes = changes, listening = true)

            when (round % 3) {
                0 -> events.emit(RealtimeEvent.RemindersChanged) // changed elsewhere, on the hub
                1 -> changes.changed(DataArea.Todos) // a notification's button on this phone
                else -> server.api.remindersChanged() // a todo saved in the app
            }

            until("heard at once, round $round") { screen.shown.isNotEmpty() }
        }
        scope.cancel()
    }

    @Test
    fun a_change_made_elsewhere_reaches_it_through_the_hub() = runTest {
        val server = Server()
        val screen = Screen()
        val events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 4)
        widget(server, screen, events = events, listening = true).refresh()

        server.todos = listOf("Dentist" to "2026-11-13T20:00:00Z")
        events.emit(RealtimeEvent.RemindersChanged)

        until("fetched again") { screen.shown.last() == (listOf("Dentist") to null) }
        scope.cancel()
    }

    @Test
    fun back_in_front_it_fetches_again_only_when_what_it_holds_is_a_few_minutes_old() = runTest {
        val server = Server()
        val screen = Screen()
        var now = morning
        val widget = widget(server, screen, clock = { now }, listening = true)
        widget.refresh()
        assertEquals(1, server.fetches())

        now += 1.minutes
        widget.refreshIfStale()
        withContext(Dispatchers.Default) { delay(300) }
        assertEquals(1, server.fetches())

        now += 5.minutes
        widget.refreshIfStale()
        until("fetched again") { server.fetches() == 2 }
        scope.cancel()
    }

    // ---- "Done" on a line, with five seconds to undo ----

    @Test
    fun a_tapped_circle_sends_nothing_for_five_seconds_then_marks_the_todo_done() = runTest {
        val server = Server()
        val screen = Screen()
        var now = morning
        val widget = widget(server, screen, clock = { now })
        widget.refresh()

        val queue = widget.markDone("o0")

        assertEquals(morning + 5.seconds, queue.nextDue)
        assertEquals(mapOf("o0" to morning + 5.seconds), screen.doneAt) // drawn struck through, with "Undo"
        now += 4.seconds
        assertEquals(morning + 5.seconds, widget.sendDue().nextDue)
        assertTrue(server.completions().isEmpty())

        now += 1.seconds
        val after = widget.sendDue()

        assertEquals(listOf("PUT /occurrences/o0/complete"), server.completions())
        assertEquals(TodayWidget.DoneQueue(nextDue = null, waitsForNetwork = false), after)
        assertEquals(listOf("Call the bank") to null, screen.shown.last()) // its line is gone before the next fetch says so
        assertTrue(screen.doneAt.isEmpty())
        assertEquals(listOf("o0"), settled) // and its reminders stay quiet on this phone
        scope.cancel()
    }

    @Test
    fun undo_inside_the_five_seconds_sends_nothing() = runTest {
        val server = Server()
        val screen = Screen()
        var now = morning
        val widget = widget(server, screen, clock = { now })
        widget.refresh()
        widget.markDone("o0")

        now += 3.seconds
        val queue = widget.undo("o0")

        assertNull(queue.nextDue)
        assertTrue(screen.doneAt.isEmpty())
        now += 10.seconds
        widget.sendDue()
        assertTrue(server.completions().isEmpty())
        assertEquals(listOf("Morning run", "Call the bank") to null, screen.shown.last())
        scope.cancel()
    }

    @Test
    fun undo_after_the_five_seconds_changes_nothing() = runTest {
        // The line is already gone then; an "Undo" Android delivers late must not look like it worked.
        val server = Server().apply { reachable = true }
        val screen = Screen()
        var now = morning
        val widget = widget(server, screen, clock = { now })
        widget.refresh()
        widget.markDone("o0")

        now += 6.seconds
        widget.undo("o0")
        widget.sendDue()

        assertEquals(listOf("PUT /occurrences/o0/complete"), server.completions())
        scope.cancel()
    }

    @Test
    fun a_circle_tapped_twice_keeps_its_first_moment_and_each_line_has_its_own() = runTest {
        val server = Server()
        val screen = Screen()
        var now = morning
        val widget = widget(server, screen, clock = { now })
        widget.refresh()
        widget.markDone("o0")

        now += 2.seconds
        widget.markDone("o0")
        val queue = widget.markDone("o1")

        assertEquals(mapOf("o0" to morning + 5.seconds, "o1" to morning + 7.seconds), screen.doneAt)
        assertEquals(morning + 5.seconds, queue.nextDue)
        now += 3.seconds
        assertEquals(morning + 7.seconds, widget.sendDue().nextDue) // the first went, the second still waits
        assertEquals(listOf("PUT /occurrences/o0/complete"), server.completions())
        scope.cancel()
    }

    @Test
    fun tapped_offline_it_is_gone_from_the_widget_and_sent_once_there_is_a_network() = runTest {
        val server = Server()
        val screen = Screen()
        var now = morning
        val settings = MapSettings()
        val widget = widget(server, screen, settings, clock = { now })
        widget.refresh()
        server.reachable = false
        widget.markDone("o0")

        now += 5.seconds
        val queue = widget.sendDue()

        assertEquals(TodayWidget.DoneQueue(nextDue = null, waitsForNetwork = true), queue)
        assertEquals(mapOf("o0" to morning + 5.seconds), screen.doneAt) // still waiting: past its moment, its line is not drawn

        // Android stopped the process meanwhile; the network is back and the job runs: what waited is in the settings.
        server.reachable = true
        now += 20.minutes
        widget(server, screen, settings, clock = { now }).refresh()

        assertEquals(listOf("PUT /occurrences/o0/complete"), server.completions())
        assertEquals(listOf("Call the bank") to null, screen.shown.last())
        assertTrue(screen.doneAt.isEmpty())
        scope.cancel()
    }

    @Test
    fun done_or_skipped_elsewhere_meanwhile_counts_as_done() = runTest {
        val server = Server().apply { completes = 409 to "10005" }
        val screen = Screen()
        var now = morning
        val widget = widget(server, screen, clock = { now })
        widget.refresh()
        widget.markDone("o0")

        now += 5.seconds
        val queue = widget.sendDue()

        assertEquals(TodayWidget.DoneQueue(nextDue = null, waitsForNetwork = false), queue)
        assertEquals(listOf("o0"), settled)
        scope.cancel()
    }

    @Test
    fun a_todo_deleted_meanwhile_is_dropped_and_the_server_busy_waits() = runTest {
        val server = Server().apply { completes = 404 to "10021" }
        val screen = Screen()
        var now = morning
        val widget = widget(server, screen, clock = { now })
        widget.refresh()
        widget.markDone("o0")
        widget.markDone("o1")
        now += 5.seconds

        assertEquals(TodayWidget.DoneQueue(nextDue = null, waitsForNetwork = false), widget.sendDue()) // refused for good: not asked again
        assertTrue(settled.isEmpty())

        // The server busy (503) is not a refusal: it waits.
        server.completes = 503 to "10058"
        widget.markDone("o1")
        now += 5.seconds
        assertEquals(TodayWidget.DoneQueue(nextDue = null, waitsForNetwork = true), widget.sendDue())
        scope.cancel()
    }

    @Test
    fun signing_out_inside_the_five_seconds_sends_nothing() = runTest {
        val server = Server()
        val screen = Screen()
        var now = morning
        val settings = MapSettings()
        val widget = widget(server, screen, settings, clock = { now })
        widget.refresh()
        widget.markDone("o0")

        server.tokens.save(null)
        widget.forget()
        now += 5.seconds
        widget.sendDue()

        assertTrue(server.completions().isEmpty())
        assertTrue(settings.keys.none { it.startsWith("kadans.widget") })
        scope.cancel()
    }

    @Test
    fun the_systems_call_made_over_and_over_costs_one_fetch_every_few_minutes() = runTest {
        // A widget's provider is exported: any app on the phone can send it the system's "update".
        val server = Server()
        val screen = Screen()
        var now = morning
        val widget = widget(server, screen, clock = { now })

        repeat(20) { widget.refreshUnlessFresh() }

        assertEquals(1, server.fetches())
        assertEquals(20, screen.shown.size) // drawn each time all the same
        now += 3.minutes
        widget.refreshUnlessFresh()
        assertEquals(2, server.fetches())
        scope.cancel()
    }

    @Test
    fun settings_button_asks_the_home_screen_to_add_the_widget() = runTest {
        val screen = Screen(placed = false)
        val widget = widget(Server(), screen)

        assertTrue(widget.canBeAdded)
        widget.addToHomeScreen()

        assertEquals(1, screen.asked)
        scope.cancel()
    }

    @Test
    fun the_free_desktop_never_asks_about_a_subscription() = runTest {
        val server = Server().apply { required = true; hasAccess = false }
        val screen = Screen()

        widget(server, screen, phone = false).refresh()

        assertTrue(server.asked.none { it.contains("billing") })
        assertEquals(listOf("Morning run", "Call the bank") to null, screen.shown.last())
        scope.cancel()
    }
}
