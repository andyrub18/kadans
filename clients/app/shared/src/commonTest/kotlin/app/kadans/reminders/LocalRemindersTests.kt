package app.kadans.reminders

import app.kadans.api.AuthTokens
import app.kadans.api.InMemoryTokenStore
import app.kadans.api.KadansApi
import app.kadans.api.KadansJson
import app.kadans.api.model.NotificationResponse
import app.kadans.api.model.ReminderCheckResponse
import app.kadans.api.model.ReminderWindowResponse
import app.kadans.api.model.UpdateSelfUserRequest
import app.kadans.api.model.UpcomingReminder
import app.kadans.push.DeviceRegistrar
import app.kadans.realtime.RealtimeEvent
import app.kadans.ui.todos.ReminderPermissionAsk
import com.russhwolf.settings.MapSettings
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The reminders a phone rings itself (ARCHITECTURE → "Reminders ring on the phone"): what a window schedules, what an
 * alarm shows (once, and not when the server says it is no longer due), a push for what already rang, a reboot, a
 * sign-out, and asking for "Alarms & reminders" once. In real time (Dispatchers.Default): the alarm's wait for the
 * server is a real timeout.
 */
class LocalRemindersTests {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val t0 = Instant.parse("2026-10-09T12:00:00Z")
    private val device = "11111111-1111-1111-1111-111111111111"

    private fun reminder(id: String, notifyAt: Instant, startsAt: Instant = notifyAt + 15.minutes) =
        UpcomingReminder(id, "todo-$id", "Title $id", "Starts at 12:15 — in 15 min", notifyAt, startsAt)

    private class FakeScheduler(var access: ReminderAccess = ReminderAccess.Ready) : ReminderScheduler {
        var scheduled: List<String> = emptyList()
        val shown = mutableListOf<String>()
        var fresh = false
        val opened = mutableListOf<ReminderAccess>()

        override fun access() = access

        override fun openSettings(missing: ReminderAccess) {
            opened += missing
        }

        override fun schedule(reminders: List<UpcomingReminder>) {
            scheduled = reminders.map { it.occurrenceId }
        }

        override fun show(reminder: UpcomingReminder) {
            shown += reminder.occurrenceId
        }

        override fun keepFresh(enabled: Boolean) {
            fresh = enabled
        }
    }

    /** The server's side: the window it hands out, its answer for each reminder, every call it got. */
    private inner class Server {
        private val lock = Mutex()
        private val received = mutableListOf<String>()
        var reachable = true
        var registered = true

        /** False: the account has no access on phones (no subscription), and the window reaches no further than now. */
        var phoneAccess = true
        var window: List<UpcomingReminder> = emptyList()
        val answers = mutableMapOf<String, ReminderCheckResponse>()
        var checksHang = false

        /** When set, a window fetch waits for it: what happens meanwhile happens mid-fetch. */
        var fetchArrived: CompletableDeferred<Unit>? = null
        var fetchReleased: CompletableDeferred<Unit>? = null

        suspend fun calls(): List<String> = lock.withLock { received.toList() }

        val engine = MockEngine { request ->
            check(reachable) { "offline" }
            val path = request.url.encodedPath
            val call = "${request.method.value} $path"
            lock.withLock { received += call }
            when {
                call == "POST /reminders/sync" && !registered ->
                    respond("""{"title":"Not Found","status":404}""", HttpStatusCode.NotFound, jsonHeaders)
                call == "POST /reminders/sync" -> {
                    fetchArrived?.complete(Unit)
                    fetchReleased?.await()
                    val body = if (phoneAccess) ReminderWindowResponse(t0, t0 + 7.days, window) else ReminderWindowResponse(t0, t0, emptyList())
                    respond(KadansJson.encodeToString(ReminderWindowResponse.serializer(), body), HttpStatusCode.OK, jsonHeaders)
                }
                call.startsWith("DELETE /reminders/sync/") -> respond("", HttpStatusCode.NoContent)
                call.startsWith("GET /reminders/") -> {
                    if (checksHang) awaitCancellation()
                    val id = path.substringAfterLast('/')
                    val answer = answers[id] ?: ReminderCheckResponse(id, due = true, notifyAt = window.firstOrNull { it.occurrenceId == id }?.notifyAt)
                    respond(KadansJson.encodeToString(ReminderCheckResponse.serializer(), answer), HttpStatusCode.OK, jsonHeaders)
                }
                call == "PUT /users/me" ->
                    respond("""{"id":"u1","username":"alice","timeZone":"America/Port-au-Prince","language":"fr"}""", HttpStatusCode.OK, jsonHeaders)
                call.startsWith("PUT /users/me/devices/") -> {
                    registered = true
                    respond(
                        """{"installationId":"$device","platform":"Android","name":"Phone","hasPushToken":false,""" +
                            """"registeredAt":"2026-10-01T00:00:00Z","lastSeenAt":"2026-10-09T12:00:00Z"}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }
                else -> error("unexpected call: $call")
            }
        }
    }

    private inner class Phone(signedIn: Boolean = true) {
        val server = Server()
        val settings = MapSettings().apply { putString("kadans.installation", device) }
        val tokens = InMemoryTokenStore(if (signedIn) AuthTokens("a", "r") else null)
        val api = KadansApi.create("http://test", tokens, server.engine)
        val scheduler = FakeScheduler()
        val store = ReminderStore(settings)
        var clock = t0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 4)
        val reminders = LocalReminders(api, DeviceRegistrar(api, settings), scheduler, store, events, { clock }, scope, 200.milliseconds)

        suspend fun fetches() = server.calls().count { it == "POST /reminders/sync" }
    }

    private fun test(signedIn: Boolean = true, body: suspend Phone.() -> Unit) = runTest {
        withContext(Dispatchers.Default) {
            val phone = Phone(signedIn)
            try {
                phone.body()
            } finally {
                phone.scope.cancel()
            }
        }
    }

    private suspend fun eventually(what: String, condition: suspend () -> Boolean) {
        withTimeout(5.seconds) { while (!condition()) delay(10) }
        assertTrue(condition(), what)
    }

    @Test
    fun a_sync_rings_the_window_from_now_on_and_keeps_it_fresh() = test {
        server.window = listOf(reminder("b", t0 + 2.hours), reminder("a", t0 + 1.hours))

        reminders.sync()

        assertEquals(listOf("a", "b"), scheduler.scheduled, "soonest first")
        assertTrue(scheduler.fresh)
        assertTrue(store.syncing)
        assertEquals(2, store.window().size)
    }

    @Test
    fun a_reminder_the_next_sync_dropped_never_rings() = test {
        server.window = listOf(reminder("a", t0 + 1.minutes), reminder("b", t0 + 1.hours))
        reminders.sync()
        server.window = listOf(reminder("b", t0 + 1.hours)) // "a" was deleted on the desktop
        reminders.sync()

        clock = t0 + 2.minutes
        reminders.ringDue() // the alarm set for "a" before the second sync

        assertEquals(emptyList(), scheduler.shown)
        assertEquals(listOf("b"), scheduler.scheduled)
    }

    @Test
    fun an_alarm_shows_what_is_due_once_and_sets_the_next() = test {
        server.window = listOf(reminder("a", t0 + 1.minutes), reminder("b", t0 + 1.minutes), reminder("c", t0 + 1.hours))
        reminders.sync()

        clock = t0 + 1.minutes
        reminders.ringDue()
        reminders.ringDue() // a second ring of the same minute

        assertEquals(listOf("a", "b"), scheduler.shown)
        assertEquals(listOf("c"), scheduler.scheduled)
    }

    @Test
    fun a_reminder_no_longer_due_stays_quiet_and_a_moved_one_fetches_its_new_time() = test {
        server.window = listOf(reminder("done", t0 + 1.minutes), reminder("moved", t0 + 1.minutes))
        reminders.sync()
        server.answers["done"] = ReminderCheckResponse("done", due = false)
        server.answers["moved"] = ReminderCheckResponse("moved", due = true, notifyAt = t0 + 3.hours)
        val before = fetches()

        clock = t0 + 1.minutes
        reminders.ringDue()

        assertEquals(emptyList(), scheduler.shown)
        eventually("the moved reminder's new time is fetched") { fetches() == before + 1 }
    }

    @Test
    fun offline_or_without_an_answer_in_time_the_alarm_rings() = test {
        server.window = listOf(reminder("a", t0 + 1.minutes), reminder("b", t0 + 2.minutes))
        reminders.sync()

        server.reachable = false
        clock = t0 + 1.minutes
        reminders.ringDue()
        server.reachable = true
        server.checksHang = true
        clock = t0 + 2.minutes
        reminders.ringDue()

        assertEquals(listOf("a", "b"), scheduler.shown)
    }

    @Test
    fun a_push_for_a_reminder_that_rang_is_dropped_and_its_alarm_stays_quiet_after_one_that_showed() = test {
        val a = reminder("a", t0 + 1.minutes)
        val b = reminder("b", t0 + 2.minutes)
        server.window = listOf(a, b)
        reminders.sync()

        clock = t0 + 1.minutes
        reminders.ringDue()
        reminders.pushed(a) // the server's push for it, from a stale window
        reminders.pushed(b) // pushed before its alarm
        clock = t0 + 2.minutes
        reminders.ringDue()

        assertEquals(listOf("a", "b"), scheduler.shown)
        assertTrue(reminders.rangHere("b", b.notifyAt))
        assertFalse(reminders.rangHere("b", b.notifyAt + 1.hours), "moved, it rings again")
    }

    @Test
    fun a_live_copy_too_late_to_be_of_use_shows_nothing() = test {
        // The hub's catch-up after a reconnect brings what arrived during the drop: an hour-old reminder is noise.
        clock = t0 + 2.hours
        reminders.pushed(reminder("old", t0, startsAt = t0 + 15.minutes))
        reminders.pushed(reminder("now", t0 + 2.hours - 1.minutes))

        assertEquals(listOf("now"), scheduler.shown)
    }

    @Test
    fun a_live_notification_carries_its_reminder() {
        val live = NotificationResponse(
            id = "n1", kind = "occurrence.due", title = "Gym", body = "Starts at 18:00 — in 15 min",
            data = mapOf("todoId" to "t1", "occurrenceId" to "o1", "scheduledAt" to "2026-10-09T18:00:00.0000000+00:00",
                "notifyAt" to "2026-10-09T17:45:00.0000000+00:00", "pomodoroTemplateId" to ""),
            createdAt = t0,
        )
        assertEquals(
            UpcomingReminder("o1", "t1", "Gym", "Starts at 18:00 — in 15 min", Instant.parse("2026-10-09T17:45:00Z"), Instant.parse("2026-10-09T18:00:00Z")),
            LocalReminders.reminderOf(live),
        )
        assertEquals(null, LocalReminders.reminderOf(live.copy(kind = "pomodoro.phase")))
    }

    @Test
    fun a_push_that_outlived_its_session_shows_nothing() = test(signedIn = false) {
        reminders.pushed(reminder("a", t0))
        assertEquals(emptyList(), scheduler.shown)
    }

    @Test
    fun a_phone_that_cannot_ring_hands_its_reminders_back_to_the_push_once() = test {
        server.window = listOf(reminder("a", t0 + 1.hours))
        reminders.sync()

        scheduler.access = ReminderAccess.ExactAlarmsOff
        reminders.sync()
        reminders.sync()

        assertEquals(1, server.calls().count { it == "DELETE /reminders/sync/$device" })
        assertEquals(emptyList(), scheduler.scheduled)
        assertFalse(scheduler.fresh)
        assertFalse(store.syncing)
    }

    @Test
    fun a_phone_the_server_cannot_be_told_keeps_trying_twice_a_day() = test {
        server.window = listOf(reminder("a", t0 + 1.hours))
        reminders.sync()

        scheduler.access = ReminderAccess.NotificationsOff
        server.reachable = false
        reminders.sync()

        assertTrue(store.syncing, "the server still believes it rings")
        assertTrue(scheduler.fresh)
        assertEquals(emptyList(), scheduler.scheduled)
    }

    @Test
    fun a_device_the_server_does_not_know_yet_registers_and_fetches_again() = test {
        server.registered = false
        server.window = listOf(reminder("a", t0 + 1.hours))

        reminders.sync()

        assertEquals(listOf("POST /reminders/sync", "PUT /users/me/devices/$device", "POST /reminders/sync"), server.calls())
        assertEquals(listOf("a"), scheduler.scheduled)
    }

    @Test
    fun after_a_reboot_a_missed_reminder_rings_if_still_of_use_and_a_stale_one_does_not() = test {
        server.window = listOf(
            reminder("stale", t0 + 1.minutes, startsAt = t0 + 16.minutes),
            reminder("missed", t0 + 50.minutes, startsAt = t0 + 65.minutes),
            reminder("ahead", t0 + 3.hours),
        )
        reminders.sync()

        // The phone was off from 12:00 to 13:00, and comes back without a network.
        server.reachable = false
        clock = t0 + 1.hours
        reminders.restore()
        assertEquals(listOf("missed", "ahead"), scheduler.scheduled, "the missed one first: its alarm rings at once")
        reminders.ringDue()

        assertEquals(listOf("missed"), scheduler.shown)
        assertEquals(listOf("ahead"), scheduler.scheduled)
    }

    @Test
    fun a_reminder_at_its_start_still_rings_a_few_minutes_late() = test {
        server.window = listOf(reminder("now", t0 + 1.minutes, startsAt = t0 + 1.minutes))
        reminders.sync()

        clock = t0 + 6.minutes // Doze held the alarm back
        reminders.ringDue()
        assertEquals(listOf("now"), scheduler.shown)
    }

    @Test
    fun signing_out_stops_everything_and_a_fetch_begun_before_lands_nowhere() = test {
        server.window = listOf(reminder("a", t0 + 1.hours))
        reminders.sync()
        reminders.permissionAnswered()

        server.fetchArrived = CompletableDeferred()
        server.fetchReleased = CompletableDeferred()
        val fetch = scope.launch { reminders.sync() }
        server.fetchArrived!!.await()
        tokens.save(null)
        reminders.forget()
        server.fetchReleased!!.complete(Unit)
        fetch.join()

        assertEquals(emptyList(), scheduler.scheduled)
        assertFalse(scheduler.fresh)
        assertEquals(emptyList(), store.window())
        assertFalse(store.syncing)
        assertTrue(store.permissionAsked, "the question belongs to the phone, not the account")
    }

    @Test
    fun the_foreground_fetches_a_window_only_when_it_is_not_fresh() = test {
        reminders.sync()
        val after = fetches()

        clock = t0 + 2.minutes
        reminders.refresh()
        delay(100)
        assertEquals(after, fetches(), "fetched two minutes ago")

        clock = t0 + 6.minutes
        reminders.refresh()
        eventually("an older window is fetched again") { fetches() == after + 1 }

        reminders.syncSoon() // a change: whatever the age
        eventually("a change always fetches") { fetches() == after + 2 }
    }

    @Test
    fun a_language_or_time_zone_change_from_this_phone_fetches_the_window_again_in_its_words() = test {
        reminders.sync()
        val before = fetches()

        api.account.update(UpdateSelfUserRequest(displayName = "Alice"))
        delay(100)
        assertEquals(before, fetches(), "a name changes no reminder")

        api.account.update(UpdateSelfUserRequest(language = "fr"))
        eventually("the window is fetched again") { fetches() == before + 1 }
    }

    @Test
    fun a_change_signalled_on_the_hub_or_a_reconnect_fetches_the_window_again() = test {
        reminders.sync()
        val before = fetches()

        events.emit(RealtimeEvent.RemindersChanged)
        eventually("a change signalled live") { fetches() == before + 1 }
        events.emit(RealtimeEvent.Reconnected) // what was signalled during the drop was lost
        eventually("back after a drop") { fetches() == before + 2 }
    }

    @Test
    fun an_account_without_access_on_phones_keeps_no_job_alive() = test {
        server.phoneAccess = false
        reminders.sync()

        assertEquals(emptyList(), scheduler.scheduled)
        assertFalse(scheduler.fresh)
    }

    @Test
    fun alarms_and_reminders_is_asked_once_when_a_todo_with_a_reminder_is_saved() = test {
        scheduler.access = ReminderAccess.ExactAlarmsOff
        val ask = ReminderPermissionAsk(reminders)
        var saves = 0

        ask.beforeSaving(notify = false) { saves++ }
        assertEquals(1, saves, "no reminder: saved, nothing asked")

        ask.beforeSaving(notify = true) { saves++ }
        assertTrue(ask.showing)
        assertEquals(1, saves, "waits for the answer")
        ask.dismiss()
        assertEquals(2, saves)
        assertTrue(reminders.shouldOfferPermission(), "dismissed is not answered")

        ask.beforeSaving(notify = true) { saves++ }
        ask.allow()
        assertEquals(3, saves)
        assertEquals(emptyList(), scheduler.opened, "the system's screen waits for the save")
        ask.saved()
        assertEquals(listOf(ReminderAccess.ExactAlarmsOff), scheduler.opened)

        ask.beforeSaving(notify = true) { saves++ }
        assertFalse(ask.showing, "asked once")
        assertEquals(4, saves)
    }

    @Test
    fun not_now_is_an_answer_too() = test {
        scheduler.access = ReminderAccess.ExactAlarmsOff
        val ask = ReminderPermissionAsk(reminders)
        ask.beforeSaving(notify = true) {}
        ask.notNow()
        ask.saved()

        assertEquals(emptyList(), scheduler.opened)
        assertFalse(reminders.shouldOfferPermission())
    }

    @Test
    fun nothing_is_asked_where_it_would_change_nothing() = test {
        for (access in listOf(ReminderAccess.Ready, ReminderAccess.NotificationsOff, ReminderAccess.Unsupported)) {
            scheduler.access = access
            assertFalse(reminders.shouldOfferPermission(), "$access")
        }
    }
}
