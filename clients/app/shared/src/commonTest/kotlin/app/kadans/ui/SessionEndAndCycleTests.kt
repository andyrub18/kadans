package app.kadans.ui

import app.kadans.api.KadansApi
import app.kadans.api.model.PomodoroPhaseType
import app.kadans.i18n.CreoleStrings
import app.kadans.i18n.EnglishStrings
import app.kadans.i18n.FrenchStrings
import app.kadans.ui.pomodoro.SessionEnd
import app.kadans.ui.templates.TemplateEditorState
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

class SessionEndAndCycleTests {
    private val portAuPrince = TimeZone.of("America/Port-au-Prince")
    private val nineAm = Instant.parse("2027-01-04T14:00:00Z") // 09:00 in Port-au-Prince (UTC−5 in January)

    @Test
    fun a_picked_time_is_its_next_occurrence_today_or_tomorrow() {
        assertEquals(Instant.parse("2027-01-04T22:00:00Z"), SessionEnd.next(LocalTime(17, 0), nineAm, portAuPrince))
        assertEquals(Instant.parse("2027-01-05T07:00:00Z"), SessionEnd.next(LocalTime(2, 0), nineAm, portAuPrince))
        // Under a minute away is too soon: tomorrow.
        assertEquals(Instant.parse("2027-01-05T14:00:00Z"), SessionEnd.next(LocalTime(9, 0), nineAm, portAuPrince))
    }

    @Test
    fun the_end_reads_as_a_clock_time_in_every_language() {
        val end = nineAm + SessionEnd.DEFAULT
        assertEquals("Ends at 21:00", SessionEnd.label(end, nineAm, portAuPrince, EnglishStrings.pomodoro))
        assertEquals("Se termine à 21:00", SessionEnd.label(end, nineAm, portAuPrince, FrenchStrings.pomodoro))
        assertEquals("Ap fini a 21:00", SessionEnd.label(end, nineAm, portAuPrince, CreoleStrings.pomodoro))
        assertEquals("Ends tomorrow at 02:00", SessionEnd.label(nineAm + 17.hours, nineAm, portAuPrince, EnglishStrings.pomodoro))
    }

    @Test
    fun the_classic_cycle_is_rounds_of_focus_and_break_the_last_break_long() {
        val cycle = TemplateEditorState.classicCycle(focus = 15, shortBreak = 5, rounds = 4, longBreak = 30)

        assertEquals(
            listOf(15, 5, 15, 5, 15, 5, 15, 30),
            cycle.map { it.durationMinutes },
        )
        assertEquals(List(4) { listOf(PomodoroPhaseType.Focus, PomodoroPhaseType.Break) }.flatten(), cycle.map { it.type })
        // No long break: the last break stays short.
        assertEquals(listOf(50, 10, 50, 10), TemplateEditorState.classicCycle(50, 10, 2, 0).map { it.durationMinutes })
    }

    @Test
    fun a_cycle_keeps_within_the_servers_limits() {
        val classic = TemplateEditorState(name = "Workday", phases = TemplateEditorState.classicCycle(15, 5, 4, 30))
        assertTrue(classic.canSave)
        assertTrue(!classic.copy(phases = TemplateEditorState.classicCycle(15, 5, 13, 30)).canSave) // 26 phases
        assertTrue(!classic.copy(phases = TemplateEditorState.classicCycle(241, 5, 1, 0)).canSave)
        assertTrue(!classic.copy(phases = emptyList()).canSave)
    }

    @Test
    fun the_end_goes_to_the_server_with_the_start_and_when_moved() = runTest {
        val requests = mutableListOf<String>()
        val run = """{"id":"r1","todoId":"t1","status":"Active","currentPhaseIndex":0,"startedAt":"2027-01-04T14:00:00Z","updatedAt":"2027-01-04T14:00:00Z","finishAt":"2027-01-04T22:00:00Z"}"""
        val api = KadansApi.create(
            "http://test",
            engine = MockEngine { request ->
                requests += request.url.toString() + " " + ((request.body as? TextContent)?.text ?: "")
                respond(run, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )

        val started = api.pomodoro.start("t1", loop = true, finishAt = Instant.parse("2027-01-04T22:00:00Z"))
        api.pomodoro.changeFinishAt("r1", Instant.parse("2027-01-04T23:00:00Z"))

        assertEquals(Instant.parse("2027-01-04T22:00:00Z"), started.finishAt)
        assertTrue("finishAt=2027-01-04T22%3A00%3A00Z" in requests[0], requests[0])
        assertTrue(requests[1].contains("/finish-at") && requests[1].contains("2027-01-04T23:00:00Z"), requests[1])
    }
}
