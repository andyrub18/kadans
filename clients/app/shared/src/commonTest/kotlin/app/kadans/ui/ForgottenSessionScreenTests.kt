package app.kadans.ui

import androidx.lifecycle.ViewModelStore
import app.kadans.api.KadansApi
import app.kadans.api.model.PomodoroRunStatus
import app.kadans.realtime.KadansRealtime
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * A hands-free session waits at the end of each cycle (ARCHITECTURE → Pomodoro): the screen asks "Another cycle?" and
 * the answer resumes it. In real time: the screen's countdown ticks on its own.
 */
class ForgottenSessionScreenTests {
    @BeforeTest
    fun before() = Dispatchers.setMain(Dispatchers.Unconfined)

    @AfterTest
    fun after() = Dispatchers.resetMain()

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    /** Cycle 1 (25 + 5 minutes) ran out at 9:30; the session ends at 14:00, when the todo's next occurrence starts. */
    private val waiting =
        """{"id":"r1","todoId":"t1","status":"Paused","currentPhaseIndex":2,"pausedRemainingSeconds":1500,""" +
            """"autoAdvance":true,"loop":true,"cycleLength":2,"startedAt":"2027-01-04T14:00:00Z",""" +
            """"pausedAt":"2027-01-04T14:30:00Z","updatedAt":"2027-01-04T14:30:00Z","finishAt":"2027-01-04T19:00:00Z",""" +
            """"occurrenceId":"o1","betweenLaps":true,"endsWithNextOccurrence":true}"""

    private val resumed = waiting
        .replace(""""status":"Paused"""", """"status":"Active","phaseEndsAt":"2027-01-04T15:07:00Z"""")
        .replace(""""betweenLaps":true""", """"betweenLaps":false""")
        .replace(""""updatedAt":"2027-01-04T14:30:00Z"""", """"updatedAt":"2027-01-04T14:42:00Z"""")

    @Test
    fun a_session_waiting_between_cycles_asks_and_another_cycle_resumes_it() = runTest {
        withContext(Dispatchers.Default) {
            val calls = mutableListOf<String>()
            val engine = MockEngine { request ->
                val call = "${request.method.value} ${request.url.encodedPath}"
                synchronized(calls) { calls += call }
                when (call) {
                    "GET /todos/t1/pomodoro/active-run" -> respond(waiting, HttpStatusCode.OK, jsonHeaders)
                    "PUT /pomodoro/runs/r1/resume" -> respond(resumed, HttpStatusCode.OK, jsonHeaders)
                    else -> error("unexpected call: $call")
                }
            }
            val api = KadansApi.create("http://test", engine = engine)
            val realtime = KadansRealtime(api)
            val store = ViewModelStore()
            val viewModel = PomodoroViewModel(api, realtime, "t1", startsOne = false)
            store.put("pomodoro", viewModel)
            try {
                viewModel.refresh()
                val shown = withTimeout(5.seconds) { viewModel.state.first { it is PomodoroUiState.Session } } as PomodoroUiState.Session
                assertTrue(shown.run.betweenLaps)
                assertTrue(shown.run.endsWithNextOccurrence)
                assertEquals(Instant.parse("2027-01-04T19:00:00Z"), shown.run.finishAt)
                assertEquals(2, PomodoroViewModel.lapOf(shown.run.currentPhaseIndex, shown.run.cycleLength), "the screen says cycle 1 is done")
                // Waiting is not the end: nothing asks about the occurrence yet.
                assertNull(viewModel.donePrompt.value)

                viewModel.resume() // "Another cycle"

                val going = withTimeout(5.seconds) {
                    viewModel.state.first { (it as? PomodoroUiState.Session)?.run?.status == PomodoroRunStatus.Active }
                } as PomodoroUiState.Session
                assertEquals(false, going.run.betweenLaps)
                assertTrue("PUT /pomodoro/runs/r1/resume" in synchronized(calls) { calls.toList() })
            } finally {
                store.clear()
                realtime.stop()
            }
        }
    }
}
