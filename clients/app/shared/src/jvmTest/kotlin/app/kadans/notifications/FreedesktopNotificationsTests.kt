@file:Suppress("FunctionName") // D-Bus member names, as the protocol spells them

package app.kadans.notifications

import app.kadans.api.KadansApi
import app.kadans.api.model.UpcomingReminder
import app.kadans.desktop.DesktopWindow
import app.kadans.i18n.Language
import app.kadans.pomodoro.CycleActions
import app.kadans.pomodoro.CycleNotice
import app.kadans.pomodoro.DesktopCycleNotices
import app.kadans.reminders.DesktopReminderNotices
import app.kadans.ui.IncomingLinks
import app.kadans.ui.focusLink
import app.kadans.ui.todoLink
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.freedesktop.dbus.bin.EmbeddedDBusDaemon
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

/**
 * Reminders with buttons on the Linux desktop, against a private bus (dbus-java's own daemon) and a stand-in for the
 * desktop's notification server: no desktop needed, as on CI. Checked by hand against COSMIC's (cosmic-notifications).
 */
class FreedesktopNotificationsTests {
    private val folder = Files.createTempDirectory("kadans-bus")
    private val address = "unix:path=${folder.resolve("bus")}"
    private lateinit var daemon: EmbeddedDBusDaemon
    private val connections = mutableListOf<AutoCloseable>()

    /** One notification as the server got it. */
    private data class Shown(val id: Long, val app: String, val title: String, val body: String, val actions: List<String>, val entry: Any?)

    /** The desktop's side: shows what it is told, with [capabilities]; [click] and [dismiss] act as the person would. */
    private class FakeServer(private val connection: DBusConnection, private val capabilities: List<String>) : Notifications {
        val shown = LinkedBlockingQueue<Shown>()
        private var next = 0L

        override fun getObjectPath() = PATH

        override fun Notify(
            appName: String,
            replacesId: UInt32,
            appIcon: String,
            summary: String,
            body: String,
            actions: List<String>,
            hints: Map<String, Variant<*>>,
            expireTimeout: Int,
        ): UInt32 {
            val id = replacesId.toLong().takeIf { it != 0L } ?: ++next
            shown.add(Shown(id, appName, summary, body, actions, hints["desktop-entry"]?.value))
            return UInt32(id)
        }

        override fun GetCapabilities() = capabilities

        override fun CloseNotification(id: UInt32) = dismiss(id.toLong())

        fun click(id: Long, key: String) = connection.sendMessage(Notifications.ActionInvoked(PATH, UInt32(id), key))

        fun dismiss(id: Long) = connection.sendMessage(Notifications.NotificationClosed(PATH, UInt32(id), UInt32(2)))
    }

    @BeforeTest
    fun startBus() {
        daemon = EmbeddedDBusDaemon("$address,listen=true")
        daemon.startInBackgroundAndWait(10_000)
    }

    @AfterTest
    fun stopBus() {
        connections.forEach { runCatching { it.close() } }
        daemon.close()
        folder.toFile().deleteRecursively()
    }

    private fun server(capabilities: List<String> = listOf("body", "actions", "body-markup", "persistence")): FakeServer {
        val connection = DBusConnectionBuilder.forAddress(address).withShared(false).build().also { connections += it }
        val server = FakeServer(connection, capabilities)
        connection.exportObject(PATH, server)
        connection.requestBusName("org.freedesktop.Notifications")
        return server
    }

    private fun notifications(): FreedesktopNotifications =
        assertNotNull(FreedesktopNotifications.connect(address)).also { connections += it }

    private fun <T> LinkedBlockingQueue<T>.next(): T = assertNotNull(poll(5, TimeUnit.SECONDS), "nothing arrived within 5 s")

    /** "Starts now", or with [headsUp] the reminder 15 minutes before. */
    private fun reminder(focus: Boolean, headsUp: Boolean = false) = UpcomingReminder(
        occurrenceId = "o1",
        todoId = "3f2b8c1e-9a4d-4e6b-8c2a-7d5e1f0a9b3c",
        title = "Write report",
        body = if (headsUp) "Starts at 09:00 — in 15 min" else "Starts now",
        notifyAt = Instant.parse(if (headsUp) "2027-01-04T13:45:00Z" else "2027-01-04T14:00:00Z"),
        startsAt = Instant.parse("2027-01-04T14:00:00Z"),
        pomodoroTemplateId = if (focus) "tpl" else null,
    )

    @Test
    fun a_notification_shows_its_buttons_and_one_click_reaches_the_app() {
        val server = server()
        val clicks = LinkedBlockingQueue<String>()

        val id = notifications().show("Write <report>", "Starts at 09:00 — in 15 min & <b>now</b>", "icon", listOf("snooze" to "In 10 min")) {
            clicks.add(it)
        }

        val shown = server.shown.next()
        assertEquals(id, shown.id)
        assertEquals("Kadans", shown.app)
        assertEquals("kadans-Kadans", shown.entry, "the server shows the installed app's name and icon")
        assertEquals("Write <report>", shown.title, "a title is never markup")
        assertEquals("Starts at 09:00 — in 15 min &amp; &lt;b&gt;now&lt;/b&gt;", shown.body, "what the person wrote, never tags")
        assertEquals(listOf("default", "", "snooze", "In 10 min"), shown.actions)

        server.click(shown.id, "snooze")
        assertEquals("snooze", clicks.next())
        server.click(shown.id, "snooze")
        assertNull(clicks.poll(300, TimeUnit.MILLISECONDS), "a notification is acted on once")
    }

    @Test
    fun only_the_notification_server_reports_a_click() {
        val server = server()
        val clicks = LinkedBlockingQueue<String>()
        notifications().show("Gym", "Starts now (18:00)", "icon", listOf("done" to "Done")) { clicks.add(it) }
        val shown = server.shown.next()

        // Another program on the session bus, posing as the server.
        val impostor = DBusConnectionBuilder.forAddress(address).withShared(false).build().also { connections += it }
        impostor.sendMessage(Notifications.ActionInvoked(PATH, UInt32(shown.id), "done"))
        assertNull(clicks.poll(300, TimeUnit.MILLISECONDS))

        server.click(shown.id, "done")
        assertEquals("done", clicks.next())
    }

    @Test
    fun a_dismissed_notification_takes_no_click() {
        val server = server()
        val clicks = LinkedBlockingQueue<String>()
        notifications().show("Gym", "Starts now (18:00)", "icon") { clicks.add(it) }
        val shown = server.shown.next()

        server.dismiss(shown.id)
        Thread.sleep(200)
        server.click(shown.id, FreedesktopNotifications.DEFAULT)

        assertNull(clicks.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun a_server_without_buttons_shows_it_plainly() {
        val server = server(capabilities = listOf("body"))
        val notifications = notifications()

        notifications.show("Gym", "Starts now & then", "icon", listOf("snooze" to "In 10 min")) {}

        assertFalse(notifications.clickable)
        val shown = server.shown.next()
        assertEquals(emptyList(), shown.actions)
        assertEquals("Starts now & then", shown.body, "no markup: the text as it is")
    }

    @Test
    fun without_a_notification_server_there_is_none() {
        assertNull(FreedesktopNotifications.connect(address))
    }

    @Test
    fun a_focus_todo_offers_start_focus_any_other_done_and_a_click_opens_the_todo() {
        val server = server()
        val notifications = notifications()
        val notices = DesktopReminderNotices(strings = { Language.En.catalog.reminders }, linux = { notifications }, actions = { null })
        val opened = LinkedBlockingQueue<Boolean>()
        DesktopWindow.show = { opened.add(true) }

        notices.show(reminder(focus = true))
        assertEquals(listOf("default", "", "start-focus", "Start focus", "snooze", "In 10 min"), server.shown.next().actions)
        notices.show(reminder(focus = false))
        val plain = server.shown.next()
        assertEquals(listOf("default", "", "done", "Done", "snooze", "In 10 min"), plain.actions)

        server.click(plain.id, FreedesktopNotifications.DEFAULT)
        opened.next()
        val link = runBlocking { withTimeout(5_000) { IncomingLinks.links.first() } }
        assertEquals(todoLink(reminder(focus = false).todoId), link)
        DesktopWindow.show = {}
    }

    @Test
    fun the_heads_up_before_the_start_has_no_buttons_and_a_click_opens_the_todo() {
        val server = server()
        val notifications = notifications()
        val notices = DesktopReminderNotices(strings = { Language.En.catalog.reminders }, linux = { notifications }, actions = { null })
        val opened = LinkedBlockingQueue<Boolean>()
        DesktopWindow.show = { opened.add(true) }

        notices.show(reminder(focus = true, headsUp = true))
        val headsUp = server.shown.next()
        assertEquals(listOf("default", ""), headsUp.actions, "no Start focus 15 minutes early, no snooze past the start")

        server.click(headsUp.id, FreedesktopNotifications.DEFAULT)
        opened.next()
        val link = runBlocking { withTimeout(5_000) { IncomingLinks.links.first() } }
        assertEquals(todoLink(reminder(focus = true).todoId), link)
        DesktopWindow.show = {}
    }

    /** The server's side of "Finish session": [up] false answers as an unreachable one would. */
    private fun cycleActions(calls: LinkedBlockingQueue<String>, up: Boolean = true): CycleActions {
        val engine = MockEngine { request ->
            calls.add("${request.method.value} ${request.url.encodedPath}")
            if (up) respond(
                """{"id":"r1","todoId":"t1","status":"Completed","currentPhaseIndex":2,"startedAt":"2027-01-04T14:00:00Z","updatedAt":"2027-01-04T14:31:00Z"}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
            else respond("""{"title":"busy","status":503}""", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return CycleActions(KadansApi.create("http://test", engine = engine)) { Language.En.catalog }
    }

    private val cycle = CycleNotice("r1", "t1", "Study", "Cycle 1 done, the next one begins (25 min of focus). Finished with the task?")

    @Test
    fun a_new_cycle_offers_finish_session_which_finishes_it_and_the_next_cycle_replaces_it() {
        val server = server()
        val calls = LinkedBlockingQueue<String>()
        val actions = cycleActions(calls)
        val notifications = notifications()
        val notices = DesktopCycleNotices(linux = { notifications }, actions = { actions })

        notices.show(cycle)
        val first = server.shown.next()
        assertEquals(listOf("default", "", "finish-session", "Finish session"), first.actions)
        assertEquals(cycle.body, first.body)
        notices.show(cycle.copy(body = "Cycle 2 done, the next one begins (25 min of focus). Finished with the task?"))
        assertEquals(first.id, server.shown.next().id, "one notice per session")

        server.click(first.id, "finish-session")
        assertEquals("PUT /pomodoro/runs/r1/finish", calls.next())
        assertNull(server.shown.poll(300, TimeUnit.MILLISECONDS), "finished: nothing more to say")
    }

    @Test
    fun a_finish_that_could_not_reach_kadans_says_so_and_keeps_its_button() {
        val server = server()
        val calls = LinkedBlockingQueue<String>()
        val actions = cycleActions(calls, up = false)
        val notifications = notifications()
        val notices = DesktopCycleNotices(linux = { notifications }, actions = { actions })

        notices.show(cycle)
        val shown = server.shown.next()
        server.click(shown.id, "finish-session")
        calls.next()

        val again = server.shown.next()
        assertEquals("${cycle.body}\n${Language.En.catalog.reminders.actionFailed}", again.body)
        assertEquals(listOf("default", "", "finish-session", "Finish session"), again.actions)
    }

    @Test
    fun a_click_on_a_cycle_notice_opens_the_session() {
        val server = server()
        val actions = cycleActions(LinkedBlockingQueue())
        val notifications = notifications()
        val notices = DesktopCycleNotices(linux = { notifications }, actions = { actions })
        val opened = LinkedBlockingQueue<Boolean>()
        DesktopWindow.show = { opened.add(true) }

        notices.show(cycle)
        server.click(server.shown.next().id, FreedesktopNotifications.DEFAULT)
        opened.next()
        val link = runBlocking { withTimeout(5_000) { IncomingLinks.links.first() } }
        assertEquals(focusLink("t1"), link)
        DesktopWindow.show = {}
    }

    private companion object {
        const val PATH = "/org/freedesktop/Notifications"
    }
}
