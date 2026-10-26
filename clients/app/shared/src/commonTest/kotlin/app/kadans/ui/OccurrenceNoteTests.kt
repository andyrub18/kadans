package app.kadans.ui

import app.kadans.api.KadansApi
import app.kadans.ui.todos.NoteFailure
import app.kadans.ui.todos.TodoDetailUiState
import app.kadans.ui.todos.TodoDetailViewModel
import app.kadans.sync.DataChanges
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.emptyFlow

/** A note on one occurrence, "how it went": what it sends, and a failure that keeps the text for another try. */
class OccurrenceNoteTests {
    private val dispatcher = StandardTestDispatcher()
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest
    fun before() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun after() = Dispatchers.resetMain()

    private val todoJson =
        """{"id":"t1","title":"Gym","description":"","status":"Scheduled","notificationEnabled":false,""" +
            """"notifyBeforeInMinutes":15,"createdAt":"2026-09-01T00:00:00Z","updatedAt":"2026-09-01T00:00:00Z"}"""

    private fun occurrencesJson(note: String?) =
        """[{"id":"o1","todoId":"t1","todoTitle":"Gym","scheduledAt":"2026-10-15T22:00:00Z",""" +
            """"originalScheduledAt":"2026-10-15T22:00:00Z","status":"Completed","remarks":${note?.let { "\"$it\"" } ?: "null"}}]"""

    /** The server: what each note PUT carried; [refusal] answers it with that problem; [reachable] false: offline. */
    private inner class Server(var refusal: String? = null, var reachable: Boolean = true) {
        val sent = mutableListOf<String>()
        var note: String? = null

        val api: KadansApi = KadansApi.create(
            "http://test",
            engine = MockEngine { request ->
                val call = "${request.method.value} ${request.url.encodedPath}"
                when {
                    call == "PUT /occurrences/o1/remark" -> {
                        check(reachable) { "offline" }
                        sent += (request.body as TextContent).text
                        refusal?.let { return@MockEngine respond(it, HttpStatusCode.BadRequest, jsonHeaders) }
                        note = Regex(""""remark":"([^"]*)"""").find(sent.last())?.groupValues?.get(1)
                        respond("{}", HttpStatusCode.OK, jsonHeaders)
                    }
                    call == "GET /todos/t1" -> respond(todoJson, HttpStatusCode.OK, jsonHeaders)
                    call == "GET /todos/t1/history" -> respond(occurrencesJson(note), HttpStatusCode.OK, jsonHeaders)
                    call == "GET /todos/t1/occurrences" -> respond("[]", HttpStatusCode.OK, jsonHeaders)
                    call == "GET /todos/t1/pomodoro/active-run" ->
                        respond("""{"title":"No active run","status":404,"errorCode":"10028"}""", HttpStatusCode.NotFound, jsonHeaders)
                    else -> respond("[]", HttpStatusCode.OK, jsonHeaders)
                }
            },
        )
    }

    @Test
    fun a_note_goes_trimmed_and_shows_under_its_occurrence() = runTest(dispatcher) {
        val server = Server()
        val viewModel = TodoDetailViewModel(server.api, "t1", DataChanges(emptyFlow()))
        viewModel.toggleHistory() // a done occurrence: how it went

        assertNull(viewModel.saveNote("o1", "  felt great, ran 5 km  "))

        assertEquals("""{"remark":"felt great, ran 5 km"}""", server.sent.single())
        val shown = assertIs<TodoDetailUiState.Content>(viewModel.state.value)
        assertEquals("felt great, ran 5 km", shown.occurrences.single().remarks)
    }

    @Test
    fun removing_it_sends_none() = runTest(dispatcher) {
        val server = Server()
        val viewModel = TodoDetailViewModel(server.api, "t1", DataChanges(emptyFlow()))

        assertNull(viewModel.saveNote("o1", null))
        assertNull(viewModel.saveNote("o1", "   "))

        // The app's JSON leaves nulls out: no remark is none, to the server as to the app.
        assertEquals(listOf("{}", "{}"), server.sent)
    }

    @Test
    fun refused_or_offline_it_says_why() = runTest(dispatcher) {
        val server = Server(
            refusal = """{"title":"Validation error","status":400,"detail":"A note is at most 4,000 characters.","errorCode":"10015"}""",
        )
        val viewModel = TodoDetailViewModel(server.api, "t1", DataChanges(emptyFlow()))

        assertEquals(NoteFailure("10015", "A note is at most 4,000 characters."), viewModel.saveNote("o1", "x"))
        server.reachable = false
        assertEquals("network", viewModel.saveNote("o1", "x")?.code)
    }
}
