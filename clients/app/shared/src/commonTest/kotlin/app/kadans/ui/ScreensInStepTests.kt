package app.kadans.ui

import app.kadans.api.KadansApi
import app.kadans.api.model.OccurrenceStatus
import app.kadans.realtime.RealtimeEvent
import app.kadans.sync.DataArea
import app.kadans.sync.DataChanges
import app.kadans.ui.todos.TodoDetailUiState
import app.kadans.ui.todos.TodoDetailViewModel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * A todo's screen shows the occurrence as the server holds it, whatever changed it: "Done" in a notification, another
 * device. Asked to complete one already done, it shows it done, never "already completed" as an error.
 */
class ScreensInStepTests {
    private val dispatcher = StandardTestDispatcher()
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest
    fun before() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun after() = Dispatchers.resetMain()

    private val todoJson =
        """{"id":"t1","title":"Gym","description":"","status":"Scheduled","notificationEnabled":true,""" +
            """"notifyBeforeInMinutes":5,"createdAt":"2026-09-01T00:00:00Z","updatedAt":"2026-09-01T00:00:00Z"}"""

    /** The server: one occurrence, changed by whatever asks; [deleted]: the todo is gone; [reachable] false: offline. */
    private inner class Server {
        var status = OccurrenceStatus.Pending
        var deleted = false
        var reachable = true
        var reads = 0

        val api: KadansApi = KadansApi.create(
            "http://test",
            engine = MockEngine { request ->
                check(reachable) { "offline" }
                when ("${request.method.value} ${request.url.encodedPath}") {
                    "GET /todos/t1" -> {
                        reads++
                        if (deleted) respond("""{"title":"Not found","status":404,"errorCode":"10001"}""", HttpStatusCode.NotFound, jsonHeaders)
                        else respond(todoJson, HttpStatusCode.OK, jsonHeaders)
                    }
                    "GET /todos/t1/occurrences" -> respond(
                        """[{"id":"o1","todoId":"t1","todoTitle":"Gym","scheduledAt":"2026-10-26T22:00:00Z",""" +
                            """"originalScheduledAt":"2026-10-26T22:00:00Z","status":"$status"}]""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                    "PUT /occurrences/o1/complete" -> when (status) {
                        OccurrenceStatus.Pending -> {
                            status = OccurrenceStatus.Completed
                            respond("{}", HttpStatusCode.OK, jsonHeaders)
                        }
                        OccurrenceStatus.Completed ->
                            respond("""{"title":"Already completed","status":409,"errorCode":"10005"}""", HttpStatusCode.Conflict, jsonHeaders)
                        OccurrenceStatus.Cancelled ->
                            respond("""{"title":"Already skipped","status":409,"errorCode":"10020"}""", HttpStatusCode.Conflict, jsonHeaders)
                    }
                    "GET /todos/t1/pomodoro/active-run" ->
                        respond("""{"title":"No active run","status":404,"errorCode":"10028"}""", HttpStatusCode.NotFound, jsonHeaders)
                    else -> respond("[]", HttpStatusCode.OK, jsonHeaders)
                }
            },
        )
    }

    private fun shown(viewModel: TodoDetailViewModel) = assertIs<TodoDetailUiState.Content>(viewModel.state.value)

    private suspend fun TodoDetailViewModel.showing(status: OccurrenceStatus) =
        state.first { it is TodoDetailUiState.Content && it.occurrences.single().status == status } as TodoDetailUiState.Content

    @Test
    fun done_from_the_notification_shows_done_on_the_open_screen() = runTest(dispatcher) {
        val server = Server()
        val changes = DataChanges(MutableSharedFlow())
        val viewModel = TodoDetailViewModel(server.api, "t1", changes)
        viewModel.refresh()
        viewModel.showing(OccurrenceStatus.Pending)

        server.status = OccurrenceStatus.Completed // "Done" in the notification, through the server
        changes.changed(DataArea.Todos) // what ReminderActions says once the server took it
        viewModel.showing(OccurrenceStatus.Completed)
    }

    @Test
    fun the_servers_word_from_another_device_shows_it_too() = runTest(dispatcher) {
        val server = Server()
        val hub = MutableSharedFlow<RealtimeEvent>()
        val viewModel = TodoDetailViewModel(server.api, "t1", DataChanges(hub))
        viewModel.refresh()
        viewModel.showing(OccurrenceStatus.Pending)

        server.status = OccurrenceStatus.Completed
        hub.emit(RealtimeEvent.DataChanged(setOf("todos")))
        viewModel.showing(OccurrenceStatus.Completed)
    }

    @Test
    fun completing_one_already_done_shows_it_done_without_an_error() = runTest(dispatcher) {
        val server = Server()
        val viewModel = TodoDetailViewModel(server.api, "t1", DataChanges(MutableSharedFlow()))
        viewModel.refresh()
        viewModel.showing(OccurrenceStatus.Pending)

        server.status = OccurrenceStatus.Completed // done elsewhere, and this screen was not told
        viewModel.completeOccurrence("o1")

        val content = viewModel.showing(OccurrenceStatus.Completed)
        assertNull(content.actionErrorCode)
    }

    @Test
    fun completing_one_skipped_elsewhere_shows_it_skipped_and_says_why() = runTest(dispatcher) {
        val server = Server()
        val viewModel = TodoDetailViewModel(server.api, "t1", DataChanges(MutableSharedFlow()))
        viewModel.refresh()
        viewModel.showing(OccurrenceStatus.Pending)

        server.status = OccurrenceStatus.Cancelled
        viewModel.completeOccurrence("o1")

        val content = viewModel.state.first {
            it is TodoDetailUiState.Content && it.occurrences.single().status == OccurrenceStatus.Cancelled && it.actionErrorCode != null
        } as TodoDetailUiState.Content
        assertEquals("10020", content.actionErrorCode)
    }

    @Test
    fun a_quiet_reload_offline_keeps_what_is_shown() = runTest(dispatcher) {
        val server = Server()
        val changes = DataChanges(MutableSharedFlow())
        val viewModel = TodoDetailViewModel(server.api, "t1", changes)
        viewModel.refresh()
        viewModel.showing(OccurrenceStatus.Pending)

        server.reachable = false
        changes.cameBack()
        runCurrent()
        testScheduler.advanceUntilIdle()

        assertEquals(OccurrenceStatus.Pending, shown(viewModel).occurrences.single().status)
    }

    @Test
    fun a_todo_deleted_on_another_device_closes_its_screen() = runTest(dispatcher) {
        val server = Server()
        val changes = DataChanges(MutableSharedFlow())
        val viewModel = TodoDetailViewModel(server.api, "t1", changes)
        viewModel.refresh()
        viewModel.showing(OccurrenceStatus.Pending)

        val closed = async { viewModel.deleted.first() }
        runCurrent()
        server.deleted = true
        changes.changed(DataArea.Todos)
        closed.await()
    }

    @Test
    fun a_budget_change_does_not_reload_a_todo() = runTest(dispatcher) {
        val server = Server()
        val changes = DataChanges(MutableSharedFlow())
        val viewModel = TodoDetailViewModel(server.api, "t1", changes)
        viewModel.refresh()
        viewModel.showing(OccurrenceStatus.Pending)
        val reads = server.reads

        changes.changed(DataArea.Budget)
        runCurrent()
        testScheduler.advanceUntilIdle()

        assertEquals(reads, server.reads)
    }
}
