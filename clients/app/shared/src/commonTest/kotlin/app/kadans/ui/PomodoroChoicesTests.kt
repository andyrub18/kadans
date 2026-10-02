package app.kadans.ui

import app.kadans.api.KadansApi
import app.kadans.ui.pomodoro.PomodoroPreference
import com.russhwolf.settings.MapSettings
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PomodoroChoicesTests {
    @Test
    fun hands_free_is_on_until_this_device_turns_it_off() {
        val settings = MapSettings()
        assertEquals(true, PomodoroPreference(settings).handsFree)

        PomodoroPreference(settings).handsFree = false

        assertEquals(false, PomodoroPreference(settings).handsFree, "remembered for the next session")
    }

    @Test
    fun a_phase_that_ran_out_here_asks_the_server_and_next_phase_skips() = runTest {
        val bodies = mutableListOf<String>()
        val run = """{"id":"r1","todoId":"t1","status":"Active","currentPhaseIndex":1,"startedAt":"2027-01-01T12:00:00Z","updatedAt":"2027-01-01T12:00:00Z"}"""
        val api = KadansApi.create(
            "http://test",
            engine = MockEngine { request ->
                bodies += (request.body as TextContent).text
                respond(run, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )

        api.pomodoro.advance("r1", expectedPhaseIndex = 0, onlyIfEnded = true)
        api.pomodoro.advance("r1", expectedPhaseIndex = 1)

        assertTrue(""""onlyIfEnded":true""" in bodies[0], bodies[0])
        assertTrue(""""onlyIfEnded":false""" in bodies[1], bodies[1])
    }
}
