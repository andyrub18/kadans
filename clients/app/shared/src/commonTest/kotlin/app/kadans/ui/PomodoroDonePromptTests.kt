package app.kadans.ui

import androidx.lifecycle.ViewModelStore
import app.kadans.api.KadansApi
import app.kadans.realtime.KadansRealtime
import app.kadans.ui.pomodoro.DonePrompt
import app.kadans.ui.pomodoro.PomodoroUiState
import app.kadans.ui.pomodoro.PomodoroViewModel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * A focus session's end asks about the occurrence it was for (ARCHITECTURE → Pomodoro): once, only while that
 * occurrence is pending, never after "End session". In real time: the screen's countdown ticks on its own.
 */
class PomodoroDonePromptTests {
    @BeforeTest
    fun before() = Dispatchers.setMain(Dispatchers.Unconfined)

    @AfterTest
    fun after() = Dispatchers.resetMain()

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun run(status: String, occurrenceId: String? = "o1") =
        """{"id":"r1","todoId":"t1","status":"$status","currentPhaseIndex":0,"startedAt":"2027-01-01T12:00:00Z",""" +
            """"updatedAt":"2027-01-01T12:25:00Z"${occurrenceId?.let { ""","occurrenceId":"$it"""" } ?: ""}}"""

    private fun occurrence(status: String) =
        """{"id":"o1","todoId":"t1","todoTitle":"Write report","scheduledAt":"2027-01-01T12:00:00Z",""" +
            """"originalScheduledAt":"2027-01-01T12:00:00Z","status":"$status"}"""

    /** The server: the todo's last session [run], its occurrence, and whether marking it done reaches it. */
    private inner class Server(private val run: String, private val occurrence: String, private val reachable: Boolean = true) {
        private val received = mutableListOf<String>()

        fun calls(): List<String> = synchronized(received) { received.toList() }

        val engine = MockEngine { request ->
            val call = "${request.method.value} ${request.url.encodedPath}"
            synchronized(received) { received += call }
            when (call) {
                "GET /todos/t1/pomodoro/active-run" ->
                    respond("""{"title":"No active run","status":404,"errorCode":"10028"}""", HttpStatusCode.NotFound, jsonHeaders)
                "GET /todos/t1/pomodoro/runs" -> respond("[$run]", HttpStatusCode.OK, jsonHeaders)
                "GET /occurrences/o1" -> respond(occurrence, HttpStatusCode.OK, jsonHeaders)
                "PUT /occurrences/o1/complete" -> {
                    check(reachable) { "offline" }
                    respond("{}", HttpStatusCode.OK, jsonHeaders)
                }
                else -> error("unexpected call: $call")
            }
        }
    }

    /** The focus screen opened by a link on a todo whose session is over: it shows the last one, and starts none. */
    private fun screen(server: Server, body: suspend (PomodoroViewModel) -> Unit) = runTest {
        withContext(Dispatchers.Default) {
            val api = KadansApi.create("http://test", engine = server.engine)
            val realtime = KadansRealtime(api)
            val store = ViewModelStore()
            val viewModel = PomodoroViewModel(api, realtime, "t1", startsOne = false)
            store.put("pomodoro", viewModel)
            try {
                viewModel.refresh()
                withTimeout(5.seconds) { viewModel.state.first { it is PomodoroUiState.Session } }
                body(viewModel)
            } finally {
                store.clear() // the countdown stops with the screen
                realtime.stop()
            }
        }
    }

    private suspend fun eventually(condition: () -> Boolean) = withTimeout(5.seconds) { while (!condition()) delay(10) }

    @Test
    fun a_finished_session_asks_about_its_occurrence_and_marks_it_done() {
        val server = Server(run("Completed"), occurrence("Pending"))
        screen(server) { viewModel ->
            val prompt = withTimeout(5.seconds) { viewModel.donePrompt.first { it != null } }
            assertEquals(DonePrompt("o1", "Write report"), prompt)

            viewModel.markDone()

            withTimeout(5.seconds) { viewModel.donePrompt.first { it?.marked == true } }
            assertTrue("PUT /occurrences/o1/complete" in server.calls())
        }
    }

    @Test
    fun offline_it_says_so_under_the_question_and_asks_again() {
        val server = Server(run("Completed"), occurrence("Pending"), reachable = false)
        screen(server) { viewModel ->
            withTimeout(5.seconds) { viewModel.donePrompt.first { it != null } }

            viewModel.markDone()

            val failed = withTimeout(5.seconds) { viewModel.donePrompt.first { it?.errorCode != null } }
            assertEquals("network", failed?.errorCode)
            assertFalse(failed!!.marked)
        }
    }

    @Test
    fun nothing_is_asked_for_an_occurrence_done_already() {
        val server = Server(run("Completed"), occurrence("Completed"))
        screen(server) { viewModel ->
            eventually { "GET /occurrences/o1" in server.calls() }
            delay(200)
            assertNull(viewModel.donePrompt.value)
        }
    }

    @Test
    fun nothing_is_asked_after_end_session_or_for_a_session_for_no_occurrence() {
        for (over in listOf(run("Cancelled"), run("Completed", occurrenceId = null))) {
            val server = Server(over, occurrence("Pending"))
            screen(server) { viewModel ->
                delay(300)
                assertNull(viewModel.donePrompt.value)
                assertFalse("GET /occurrences/o1" in server.calls())
            }
        }
    }
}
