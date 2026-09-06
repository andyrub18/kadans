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
                val run = api.pomodoro.start(todo.id, autoAdvance = true)
                api.pomodoro.pause(run.id)

                val event = pushed.await()
                assertEquals(todo.id, event.run.todoId)
                println("realtime: received pomodoro.run.changed (status ${event.run.status})")
                assertTrue(realtime.connected.value)

                // Advancing a hands-free run must also push a localized notification
                // (that is what becomes the OS notification on desktop).
                val notified = async {
                    withTimeout(10_000) {
                        realtime.events.first { it is RealtimeEvent.NotificationReceived }
                            as RealtimeEvent.NotificationReceived
                    }
                }
                delay(300)
                api.pomodoro.resume(run.id)
                api.pomodoro.advance(run.id, expectedPhaseIndex = 0)
                val notification = notified.await()
                println("realtime: received notification '${notification.notification.body}'")

                // The advance may have completed a short run — a completed run can't be cancelled.
                runCatching { api.pomodoro.cancel(run.id) }
                runCatching { api.todos.cancel(todo.id, "realtime smoke cleanup") }
            } finally {
                realtime.stop()
                runCatching { api.auth.logout() }
            }
        }
    }

    /**
     * The slow one (~90s): a hands-free run with a 1-minute phase must be advanced BY THE
     * QUARTZ JOB and the new phase must arrive over the socket — the exact user-visible
     * "advances by itself" promise. Opt in with KADANS_SMOKE_SLOW=1.
     */
    @Test
    fun quartz_job_advances_hands_free_run_and_pushes_it_live() {
        val baseUrl = System.getenv("KADANS_API_URL") ?: return
        if (System.getenv("KADANS_SMOKE_SLOW") != "1") {
            println("KADANS_SMOKE_SLOW not set; skipping the slow hands-free smoke")
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
                val todo = api.todos.createOneTime(
                    CreateOneTimeTodo(title = "hands-free smoke", dueDate = Clock.System.now() + 1.hours),
                )
                val template = api.pomodoro.createTemplate(
                    CreatePomodoroTemplate(
                        "hands-free smoke 1m",
                        listOf(
                            CreatePomodoroPhase(PomodoroPhaseType.Focus, 1),
                            CreatePomodoroPhase(PomodoroPhaseType.Break, 1),
                        ),
                    ),
                )
                api.pomodoro.attachTemplate(todo.id, template.id)

                val advanced = async {
                    withTimeout(120_000) {
                        realtime.events.first {
                            it is RealtimeEvent.PomodoroRunChanged &&
                                it.run.todoId == todo.id &&
                                it.run.currentPhaseIndex > 0
                        } as RealtimeEvent.PomodoroRunChanged
                    }
                }
                delay(300)
                val run = api.pomodoro.start(todo.id, autoAdvance = true, loop = true)
                println("hands-free: run started, waiting for the job to advance phase 0…")

                val event = advanced.await()
                assertTrue(event.run.currentPhaseIndex > 0, "job should have advanced past phase 0")
                println("hands-free: job advanced to phase ${event.run.currentPhaseIndex}, pushed live")

                api.pomodoro.cancel(run.id)
                api.todos.cancel(todo.id, "hands-free smoke cleanup")
                api.pomodoro.deleteTemplate(template.id)
            } finally {
                realtime.stop()
                runCatching { api.auth.logout() }
            }
        }
    }
}
