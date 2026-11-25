package app.kadans.pomodoro

import app.kadans.api.KadansApi
import app.kadans.i18n.Language
import app.kadans.sound.KadansSound
import app.kadans.sync.DataArea
import app.kadans.sync.DataChanges
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest

/** A looping session's cycle done (ARCHITECTURE → Pomodoro): the notice that asks for another, and its two buttons. */
class CycleNoticesTests {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private val data = mapOf(
        "todoId" to "t1", "runId" to "r1", "status" to "Paused", "currentPhaseIndex" to "2", "cycleDone" to "1", "occurrenceId" to "o1",
    )

    private val run = """{"id":"r1","todoId":"t1","status":"Completed","currentPhaseIndex":2,"startedAt":"2027-01-04T14:00:00Z","updatedAt":"2027-01-04T14:31:00Z"}"""
    private val over = """{"title":"Pomodoro run invalid state","status":400,"errorCode":"10029"}"""

    @Test
    fun a_cycle_done_is_a_notice_and_any_other_phase_change_is_not() {
        val body = "Cycle 1 done. Another one? The session waits for you."

        assertEquals(CycleNotice("r1", "t1", "Study", body, "o1"), CycleNotice.of("pomodoro.phase.completed", data, "Study", body))
        assertEquals(null, CycleNotice.of("pomodoro.phase.completed", data - "occurrenceId", "Study", body)?.occurrenceId)
        assertNull(CycleNotice.of("pomodoro.phase.completed", data - "cycleDone", "Study", "Break — 5 min"), "inside a cycle")
        assertNull(CycleNotice.of("occurrence.due", data, "Study", body))
        assertNull(CycleNotice.of("pomodoro.phase.completed", data - "runId", "Study", body))
        assertNull(CycleNotice.of("pomodoro.phase.completed", null, "Study", body))
    }

    @Test
    fun a_sessions_notice_is_told_by_the_sound_it_asks_for_and_names_its_session() {
        val phase = data - "cycleDone" - "occurrenceId" + ("sound" to "break-over")

        // A phase's end: what a phone builds itself, in the channel that has that sound (ARCHITECTURE → "Sounds").
        assertEquals(
            PhaseNotice("r1", "t1", "Study", "Focus — 25 min", KadansSound.BreakOver, key = "pomodoro.phase.completed|r1|2|Paused|null"),
            PhaseNotice.of("pomodoro.phase.completed", phase, "Study", "Focus — 25 min"),
        )
        // "Time's up" and "finished" are other kinds, and notices all the same.
        assertEquals(KadansSound.FocusOver, PhaseNotice.of("pomodoro.time.up", phase + ("sound" to "focus-over"), "Study", "Time's up")?.sound)
        // A cycle done has its two buttons.
        val body = "Cycle 1 done. Another one? The session waits for you."
        val done = PhaseNotice.of("pomodoro.phase.completed", data + ("sound" to "focus-over"), "Study", body)
        assertEquals(CycleNotice("r1", "t1", "Study", body, "o1"), done?.cycle)
        assertNull(PhaseNotice.of("pomodoro.phase.completed", phase, "Study", "Focus — 25 min")?.cycle, "inside a cycle: no button")

        // Any other notification: no sound asked for, one nobody knows, a reminder's (reminders ring their own way),
        // or no session to belong to.
        assertNull(PhaseNotice.of("budget.limit", phase - "sound", "Groceries", "80% used"))
        assertNull(PhaseNotice.of("pomodoro.phase.completed", phase + ("sound" to "fanfare"), "Study", "Focus"))
        assertNull(PhaseNotice.of("occurrence.due", phase + ("sound" to "reminder"), "Study", "Starts now"))
        assertNull(PhaseNotice.of("pomodoro.phase.completed", phase - "runId", "Study", "Focus"))
        assertNull(PhaseNotice.of("pomodoro.phase.completed", phase, null, "Focus"))
        assertNull(PhaseNotice.of("pomodoro.phase.completed", null, "Study", "Focus"))
    }

    @Test
    fun a_notice_brought_twice_is_one_and_the_next_phases_is_another() {
        val live = data - "cycleDone" + ("sound" to "break-over")
        // The push carries the same data, with the kind and the words beside it.
        val pushed = live + mapOf("kind" to "pomodoro.phase.completed", "title" to "Study", "body" to "Focus — 25 min")
        fun key(kind: String, of: Map<String, String>) = PhaseNotice.of(kind, of, "Study", "Focus — 25 min")!!.key

        assertEquals(key("pomodoro.phase.completed", live), key("pomodoro.phase.completed", pushed))
        assertTrue(key("pomodoro.phase.completed", live) != key("pomodoro.phase.completed", live + ("currentPhaseIndex" to "3")), "the next phase")
        assertTrue(key("pomodoro.phase.completed", live) != key("pomodoro.time.up", live), "its time up, then its end")
        assertTrue(key("pomodoro.phase.completed", live) != key("pomodoro.phase.completed", live + ("runId" to "r2")), "another session")
        assertTrue(key("pomodoro.phase.completed", live) != key("pomodoro.phase.completed", live + ("cycleDone" to "1")))
    }

    @Test
    fun whichever_brings_a_notice_first_shows_it_and_the_other_is_dropped_while_a_push_can_still_come() {
        val t0 = Instant.parse("2027-01-04T14:25:00Z")
        val shown = ShownOnce(keptFor = 15.minutes)

        assertTrue(shown.first("phase 2", t0), "the live connection, a moment after the phase ended")
        assertFalse(shown.first("phase 2", t0 + 2.seconds), "its push, two seconds later")
        assertTrue(shown.first("phase 3", t0 + 5.minutes))
        assertFalse(shown.first("phase 2", t0 + 14.minutes), "a push that waited for a phone out of reach")
        assertTrue(shown.first("phase 2", t0 + 16.minutes), "forgotten once no push for it can come")
    }

    @Test
    fun the_live_connection_shows_what_was_just_made_and_leaves_a_catch_up_to_the_push() {
        val made = Instant.parse("2027-01-04T14:25:00Z")

        assertTrue(PhaseNotice.isLive(made, now = made + 1.seconds))
        assertTrue(PhaseNotice.isLive(made, now = made - 3.seconds), "a phone whose clock runs behind")
        assertFalse(PhaseNotice.isLive(made, now = made + 5.minutes), "the app back in front, catching up on what it missed")
    }

    /** The server: each call's answer by "METHOD path"; a call it has no answer for is unreachable. */
    private fun actions(answers: Map<String, Pair<HttpStatusCode, String>>, calls: MutableList<String>, changes: DataChanges = DataChanges(emptyFlow())): CycleActions {
        val engine = MockEngine { request ->
            val call = "${request.method.value} ${request.url.encodedPath}"
            calls += call
            val (status, body) = answers[call] ?: error("offline")
            respond(body, status, jsonHeaders)
        }
        return CycleActions(KadansApi.create("http://test", engine = engine), changes) { Language.Fr.catalog }
    }

    private val notice = CycleNotice("r1", "t1", "Study", "Cycle 1 done", "o1")

    @Test
    fun another_cycle_resumes_the_session() = runTest {
        val calls = mutableListOf<String>()

        assertTrue(actions(mapOf("PUT /pomodoro/runs/r1/resume" to (HttpStatusCode.OK to run)), calls).anotherCycle(notice))
        assertEquals(listOf("PUT /pomodoro/runs/r1/resume"), calls)
        // Over already (ended by itself, finished elsewhere): the notice is of no more use. Offline: it stays.
        assertTrue(actions(mapOf("PUT /pomodoro/runs/r1/resume" to (HttpStatusCode.BadRequest to over)), mutableListOf()).anotherCycle(notice))
        assertFalse(actions(emptyMap(), mutableListOf()).anotherCycle(notice))
    }

    @Test
    fun done_finishes_the_session_marks_its_occurrence_done_and_tells_the_screens() = runTest {
        val calls = mutableListOf<String>()
        val changes = DataChanges(emptyFlow())
        val told = CompletableDeferred<Unit>()
        changes.reloadOn(backgroundScope, DataArea.Todos) { told.complete(Unit) } // a screen, listening before the button is pressed

        val done = actions(
            mapOf(
                "PUT /pomodoro/runs/r1/finish" to (HttpStatusCode.OK to run),
                "PUT /occurrences/o1/complete" to (HttpStatusCode.OK to "{}"),
            ),
            calls,
            changes,
        ).done(notice)

        assertTrue(done)
        assertEquals(listOf("PUT /pomodoro/runs/r1/finish", "PUT /occurrences/o1/complete"), calls)
        told.await()
    }

    @Test
    fun done_again_after_a_failure_or_on_something_done_already_is_done() = runTest {
        val already = """{"title":"Task already completed","status":400,"errorCode":"10005"}"""
        assertTrue(
            actions(
                mapOf(
                    "PUT /pomodoro/runs/r1/finish" to (HttpStatusCode.BadRequest to over),
                    "PUT /occurrences/o1/complete" to (HttpStatusCode.BadRequest to already),
                ),
                mutableListOf(),
            ).done(notice)
        )
        // Finished, but the occurrence could not be marked: not done yet, the notice stays.
        assertFalse(actions(mapOf("PUT /pomodoro/runs/r1/finish" to (HttpStatusCode.OK to run)), mutableListOf()).done(notice))
        // A session for no occurrence: Done finishes it.
        val calls = mutableListOf<String>()
        assertTrue(actions(mapOf("PUT /pomodoro/runs/r1/finish" to (HttpStatusCode.OK to run)), calls).done(notice.copy(occurrenceId = null)))
        assertEquals(listOf("PUT /pomodoro/runs/r1/finish"), calls)
    }

    @Test
    fun its_words_are_in_the_apps_language() {
        val strings = actions(emptyMap(), mutableListOf()).strings
        assertEquals("Un autre cycle", strings.pomodoro.anotherCycle)
        assertEquals("Cycle 2 terminé", strings.pomodoro.cycleDone(2))
    }
}
