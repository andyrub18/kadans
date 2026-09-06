package app.kadans.realtime

import app.kadans.api.KadansApi
import app.kadans.api.model.CreateOneTimeTodo
import app.kadans.api.model.CreatePomodoroPhase
import app.kadans.api.model.CreatePomodoroTemplate
import app.kadans.api.model.PomodoroPhaseType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Live check of the hand-rolled SignalR client when KADANS_API_URL is set (dev:
 * http://localhost:5199); silently skipped otherwise so CI needs no server.
 */
class RealRealtimeSmokeTest {
    @Test
    fun hub_pushes_pomodoro_run_changes_live() {
        val baseUrl = System.getenv("KADANS_API_URL") ?: run {
            println("KADANS_API_URL not set; skipping live realtime smoke test")
            return
        }
        val user = System.getenv("KADANS_API_USER") ?: "admin"
        val password = System.getenv("KADANS_API_PASSWORD") ?: "Admin123!"

        runBlocking {
            val api = KadansApi.create(baseUrl)
            api.auth.login(user, password)
            val realtime = KadansRealtime(api)
            realtime.start()
            try {
                withTimeout(10_000) { realtime.connected.first { it } }
                println("realtime: connected to the hub")

                val todo = api.todos.createOneTime(
                    CreateOneTimeTodo(title = "realtime smoke", dueDate = Clock.System.now() + 1.hours),
                )
                val template = api.pomodoro.templates().firstOrNull()
                    ?: api.pomodoro.createTemplate(
                        CreatePomodoroTemplate("smoke", listOf(CreatePomodoroPhase(PomodoroPhaseType.Focus, 25))),
                    )
                api.pomodoro.attachTemplate(todo.id, template.id)

                val pushed = async {
                    withTimeout(10_000) {
                        realtime.events.first {
                            it is RealtimeEvent.PomodoroRunChanged && it.run.todoId == todo.id
                        } as RealtimeEvent.PomodoroRunChanged
                    }
                }
                delay(300) // let the collector subscribe before triggering
                val run = api.pomodoro.start(todo.id)
                api.pomodoro.pause(run.id)

                val event = pushed.await()
                assertEquals(todo.id, event.run.todoId)
                println("realtime: received pomodoro.run.changed (status ${event.run.status})")
                assertTrue(realtime.connected.value)

                api.pomodoro.cancel(run.id)
                api.todos.cancel(todo.id, "realtime smoke cleanup")
            } finally {
                realtime.stop()
                runCatching { api.auth.logout() }
            }
        }
    }
}
