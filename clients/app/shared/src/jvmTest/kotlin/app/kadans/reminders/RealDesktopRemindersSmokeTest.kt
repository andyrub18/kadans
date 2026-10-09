package app.kadans.reminders

import app.kadans.api.KadansApi
import app.kadans.api.model.CreateOneTimeTodo
import app.kadans.api.model.UpcomingReminder
import app.kadans.push.DeviceRegistrar
import app.kadans.realtime.KadansRealtime
import com.russhwolf.settings.MapSettings
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The desktop app's reminders against a running API, when KADANS_API_URL is set (dev: http://localhost:5199; user
 * KADANS_API_USER / KADANS_API_PASSWORD, default smoke / Smoke123!); skipped otherwise, so CI needs no server. A
 * reminder a minute away reaches the window over the hub, then rings from the timer with the server out of reach.
 * About 80 seconds.
 */
class RealDesktopRemindersSmokeTest {
    @Test
    fun a_reminder_rings_from_the_timer_with_the_server_out_of_reach() {
        val baseUrl = System.getenv("KADANS_API_URL") ?: run {
            println("KADANS_API_URL not set; skipping the live desktop reminders smoke test")
            return
        }
        val user = System.getenv("KADANS_API_USER") ?: "smoke"
        val password = System.getenv("KADANS_API_PASSWORD") ?: "Smoke123!"

        runBlocking {
            var offline = false
            // Port 9 (discard) refuses at once: the app's calls fail as they do without a network.
            val api = KadansApi.create(baseUrlProvider = { if (offline) "http://127.0.0.1:9" else baseUrl })
            api.auth.login(user, password)
            val settings = MapSettings()
            val registrar = DeviceRegistrar(api, settings)
            registrar.register()
            val shown = Collections.synchronizedList(mutableListOf<UpcomingReminder>())
            val desktop = DesktopReminderScheduler(onDue = { reminders.ringDue() })
            val scheduler = object : ReminderScheduler by desktop {
                override fun show(reminder: UpcomingReminder) {
                    shown += reminder
                }
            }
            val realtime = KadansRealtime(api)
            reminders = LocalReminders(api, registrar, scheduler, ReminderStore(settings), realtime.events)
            realtime.start()
            var todoId: String? = null
            try {
                withTimeout(10.seconds) { realtime.connected.first { it } }
                reminders.sync()

                // Whole seconds: the reminder falls 70 s from now.
                val due = Instant.fromEpochSeconds(Clock.System.now().epochSeconds + 130)
                val todo = api.todos.createOneTime(
                    CreateOneTimeTodo(title = "Desktop timer smoke", dueDate = due, notificationEnabled = true, notifyBeforeInMinutes = 1),
                )
                todoId = todo.id
                val notifyAt = due - 60.seconds
                withTimeout(10.seconds) {
                    while (ReminderStore(settings).window().none { it.todoId == todo.id }) delay(200)
                }
                println("desktop reminders: the hub's signal brought it into the window")

                offline = true
                realtime.stop()
                withTimeout(notifyAt - Clock.System.now() + 10.seconds) {
                    while (shown.none { it.todoId == todo.id }) delay(200)
                }
                val rang = shown.first { it.todoId == todo.id }
                val late = Clock.System.now() - notifyAt
                println("desktop reminders: rang offline, ${late.inWholeMilliseconds} ms after its time: ${rang.title} — ${rang.body}")
                assertTrue(late < 5.seconds, "rang $late after its time")
            } finally {
                offline = false
                realtime.stop()
                todoId?.let { runCatching { api.todos.delete(it) } }
                registrar.installationId()?.let { runCatching { api.account.removeDevice(it) } }
            }
        }
    }

    private lateinit var reminders: LocalReminders
}
