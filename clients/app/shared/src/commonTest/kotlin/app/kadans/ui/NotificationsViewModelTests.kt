package app.kadans.ui

import app.kadans.api.KadansApi
import app.kadans.api.model.NotificationResponse
import app.kadans.realtime.RealtimeEvent
import app.kadans.ui.home.HomeViewModel
import app.kadans.ui.notifications.NotificationsUiState
import app.kadans.ui.notifications.NotificationsViewModel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone

class NotificationsViewModelTests {
    private val dispatcher = StandardTestDispatcher()
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest
    fun before() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun after() = Dispatchers.resetMain()

    private fun note(id: String, read: Boolean = false) =
        """{"id":"$id","kind":"todo.reminder","title":"Water the plants","body":"Starts at 09:00","data":{"todoId":"t1"},""" +
            """"createdAt":"2026-09-17T13:00:00Z","readAt":${if (read) "\"2026-09-17T13:05:00Z\"" else "null"}}"""

    private class Calls {
        val paths = mutableListOf<String>()
        var failWrites = false
    }

    private fun api(calls: Calls, list: String): KadansApi = KadansApi.create(
        "http://test",
        engine = MockEngine { request ->
            calls.paths += request.method.value + " " + request.url.encodedPath
            if (request.method == HttpMethod.Put && calls.failWrites) respond("""{"title":"boom","status":500}""", HttpStatusCode.InternalServerError, jsonHeaders)
            else respond(if (request.method == HttpMethod.Get) list else "{}", HttpStatusCode.OK, jsonHeaders)
        },
    )

    private suspend fun NotificationsViewModel.content() = state.first { it is NotificationsUiState.Content } as NotificationsUiState.Content

    private suspend fun awaitCall(calls: Calls, path: String) =
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (path !in calls.paths) delay(10) } }

    @Test
    fun loads_newest_first_and_counts_unread() = runTest(dispatcher) {
        val viewModel = NotificationsViewModel(api(Calls(), "[${note("n2")},${note("n1", read = true)}]"), MutableSharedFlow())

        viewModel.refresh()

        val content = viewModel.content()
        assertEquals(listOf("n2", "n1"), content.items.map { it.id })
        assertEquals(1, content.unread)
        assertEquals(false, content.hasMore)
    }

    @Test
    fun reading_is_instant_and_the_server_is_told() = runTest(dispatcher) {
        val calls = Calls()
        val viewModel = NotificationsViewModel(api(calls, "[${note("n1")}]"), MutableSharedFlow())
        viewModel.refresh()
        viewModel.content()

        viewModel.markRead("n1")

        assertEquals(0, viewModel.content().unread) // before any reply
        awaitCall(calls, "PUT /notifications/n1/read")
    }

    @Test
    fun mark_all_read_clears_every_dot_with_one_call() = runTest(dispatcher) {
        val calls = Calls()
        val viewModel = NotificationsViewModel(api(calls, "[${note("n2")},${note("n1")}]"), MutableSharedFlow())
        viewModel.refresh()
        viewModel.content()

        viewModel.markAllRead()

        assertEquals(0, viewModel.content().unread)
        awaitCall(calls, "PUT /notifications/read-all")
        assertEquals(1, calls.paths.count { it.startsWith("PUT") })
    }

    @Test
    fun a_failed_write_resyncs_from_the_server_instead_of_lying() = runTest(dispatcher) {
        val calls = Calls().apply { failWrites = true }
        val viewModel = NotificationsViewModel(api(calls, "[${note("n1")}]"), MutableSharedFlow())
        viewModel.refresh()
        viewModel.content()

        viewModel.markRead("n1")

        // optimistic 0, then the reload brings the truth back: still unread on the server
        assertEquals(1, viewModel.state.first { it is NotificationsUiState.Content && it.unread == 1 && calls.paths.count { p -> p.startsWith("GET") } == 2 }
            .let { (it as NotificationsUiState.Content).unread })
    }

    @Test
    fun a_live_notification_lands_on_top_once() = runTest(dispatcher) {
        val events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 4)
        val viewModel = NotificationsViewModel(api(Calls(), "[${note("n1", read = true)}]"), events)
        viewModel.refresh()
        viewModel.content()
        dispatcher.scheduler.advanceUntilIdle() // the collector is subscribed

        val live = NotificationResponse("n9", "pomodoro.phase.completed", "Deep work", "Break — 5 min", null, Instant.parse("2026-09-17T14:00:00Z"))
        events.emit(RealtimeEvent.NotificationReceived(live))
        events.emit(RealtimeEvent.NotificationReceived(live)) // the hub may redeliver after a reconnect
        dispatcher.scheduler.advanceUntilIdle()

        val content = assertIs<NotificationsUiState.Content>(viewModel.state.value)
        assertEquals(listOf("n9", "n1"), content.items.map { it.id })
        assertEquals(1, content.unread)
    }

    @Test
    fun timestamps_are_shown_in_the_devices_zone() {
        val at = Instant.parse("2026-09-17T03:05:00Z")

        assertEquals("2026-09-16 23:05", NotificationsViewModel.formatTimestamp(at, TimeZone.of("America/Port-au-Prince")))
        assertEquals("2026-09-17 03:05", NotificationsViewModel.formatTimestamp(at, TimeZone.UTC))
    }

    @Test
    fun the_badge_stays_a_badge() {
        assertEquals("7", HomeViewModel.badgeText(7))
        assertEquals("99", HomeViewModel.badgeText(99))
        assertEquals("99+", HomeViewModel.badgeText(100))
        assertTrue(HomeViewModel.badgeText(12_345).length <= 3)
    }
}
