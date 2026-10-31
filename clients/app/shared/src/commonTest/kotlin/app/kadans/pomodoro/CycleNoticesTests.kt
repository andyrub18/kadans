package app.kadans.pomodoro

import app.kadans.api.KadansApi
import app.kadans.i18n.Language
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** A looping session's new cycle (ARCHITECTURE → Pomodoro): the notice that offers "Finish session", and that button. */
class CycleNoticesTests {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private val data = mapOf("todoId" to "t1", "runId" to "r1", "status" to "Active", "currentPhaseIndex" to "2", "cycleDone" to "1")

    @Test
    fun a_new_cycle_is_a_notice_and_any_other_phase_change_is_not() {
        val body = "Cycle 1 done, the next one begins (25 min of focus). Finished with the task?"

        assertEquals(CycleNotice("r1", "t1", "Study", body), CycleNotice.of("pomodoro.phase.completed", data, "Study", body))
        assertNull(CycleNotice.of("pomodoro.phase.completed", data - "cycleDone", "Study", "Break — 5 min"), "inside a cycle")
        assertNull(CycleNotice.of("occurrence.due", data, "Study", body))
        assertNull(CycleNotice.of("pomodoro.phase.completed", data - "runId", "Study", body))
        assertNull(CycleNotice.of("pomodoro.phase.completed", null, "Study", body))
    }

    private fun actions(status: HttpStatusCode, body: String, calls: MutableList<String> = mutableListOf()): CycleActions {
        val engine = MockEngine { request ->
            calls += "${request.method.value} ${request.url.encodedPath}"
            respond(body, status, jsonHeaders)
        }
        return CycleActions(KadansApi.create("http://test", engine = engine)) { Language.Fr.catalog }
    }

    private val notice = CycleNotice("r1", "t1", "Study", "Cycle 1 done")

    @Test
    fun finish_session_finishes_it_on_the_server() = runTest {
        val calls = mutableListOf<String>()
        val run = """{"id":"r1","todoId":"t1","status":"Completed","currentPhaseIndex":2,"startedAt":"2027-01-04T14:00:00Z","updatedAt":"2027-01-04T14:31:00Z"}"""

        assertTrue(actions(HttpStatusCode.OK, run, calls).finish(notice))
        assertEquals(listOf("PUT /pomodoro/runs/r1/finish"), calls)
    }

    @Test
    fun a_session_over_already_counts_as_finished_and_an_unreachable_server_does_not() = runTest {
        val over = """{"title":"Pomodoro run invalid state","status":400,"errorCode":"10029"}"""
        assertTrue(actions(HttpStatusCode.BadRequest, over).finish(notice), "finished on another device, or ended by itself")
        val gone = """{"title":"Pomodoro run not found","status":404,"errorCode":"10028"}"""
        assertTrue(actions(HttpStatusCode.NotFound, gone).finish(notice))
        assertFalse(actions(HttpStatusCode.ServiceUnavailable, """{"title":"busy","status":503}""").finish(notice))
    }

    @Test
    fun its_words_are_in_the_apps_language() {
        assertEquals("Terminer la session", actions(HttpStatusCode.OK, "{}").strings.finishSession)
    }
}
