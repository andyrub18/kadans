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
import app.kadans.sync.DataChanges
import app.kadans.sync.DataArea
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.channels.Channel

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
        val bodies = mutableListOf<String>()
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
            bodies += reminder.body
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

        /** Each check's occurrence and the reminder time it asked about. */
        val checked = mutableListOf<Pair<String, Instant?>>()

        /** "Start focus": the session's answer, or the problem's error code; the query of each start. */
        var startRefusedWith: String? = null
        val starts = mutableListOf<String>()

        /** "Done": the problem's error code, or null for done. */
        var completeRefusedWith: String? = null

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
                    lock.withLock { checked += id to request.url.parameters["at"]?.let(Instant::parse) }
                    val mine = window.filter { it.occurrenceId == id }
                    val answer = answers[id] ?: ReminderCheckResponse(id, due = true, notifyAt = mine.firstOrNull()?.notifyAt, startsAt = mine.firstOrNull()?.startsAt)
                    respond(KadansJson.encodeToString(ReminderCheckResponse.serializer(), answer), HttpStatusCode.OK, jsonHeaders)
                }
                call.startsWith("POST /todos/") && path.endsWith("/pomodoro/start") -> {
                    lock.withLock { starts += request.url.encodedQuery }
                    when (val code = startRefusedWith) {
                        null -> respond(
                            """{"id":"run-1","todoId":"todo-a","status":"Active","currentPhaseIndex":0,""" +
                                """"startedAt":"2026-10-09T12:00:00Z","updatedAt":"2026-10-09T12:00:00Z","occurrenceId":"a"}""",
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                        else -> respond("""{"title":"Refused","status":400,"errorCode":"$code"}""", HttpStatusCode.BadRequest, jsonHeaders)
                    }
                }
                call.startsWith("PUT /occurrences/") && path.endsWith("/complete") -> when (val code = completeRefusedWith) {
                    null -> respond("{}", HttpStatusCode.OK, jsonHeaders)
                    else -> respond("""{"title":"Refused","status":400,"errorCode":"$code"}""", HttpStatusCode.BadRequest, jsonHeaders)
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
        val reminders = LocalReminders(
            api, DeviceRegistrar(api, settings), scheduler, store, events, { clock }, scope, 200.milliseconds,
            snoozedText = { reminder, now -> "${reminder.title}, again at $now" },
        )

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
        // Moved: its reminder at this time is none of its reminders any more; they are now hours later.
        server.answers["moved"] = ReminderCheckResponse("moved", due = false, notifyAt = t0 + 3.hours, startsAt = t0 + 3.hours + 15.minutes)
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
    fun a_change_made_here_fetches_the_window_without_waiting_for_the_hub() = test {
        reminders.sync()
        val before = fetches()

        // Done from this phone, the hub's signal never arriving (a dropped connection): the window is fetched anyway.
        api.todos.completeOccurrence("o9")

        eventually("the window is fetched again") { fetches() == before + 1 }
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

    /** An occurrence starting at 12:06, reminded at 12:01: its two reminders, as the server's window holds them. */
    private fun twoReminders(id: String = "a", template: String? = null): List<UpcomingReminder> {
        val early = reminder(id, t0 + 1.minutes, startsAt = t0 + 6.minutes).copy(pomodoroTemplateId = template)
        return listOf(early, early.copy(notifyAt = t0 + 6.minutes, body = "Starts now (12:06)"))
    }

    @Test
    fun an_occurrence_rings_before_its_start_and_at_it_each_checked_by_its_time() = test {
        server.window = twoReminders()
        reminders.sync()

        clock = t0 + 1.minutes
        reminders.ringDue()
        clock = t0 + 6.minutes
        reminders.ringDue()
        reminders.ringDue()

        assertEquals(listOf("Starts at 12:15 — in 15 min", "Starts now (12:06)"), scheduler.bodies)
        assertEquals(listOf("a" to t0 + 1.minutes, "a" to t0 + 6.minutes), server.checked.toList())
    }

    @Test
    fun while_its_focus_session_runs_a_reminder_stays_quiet_and_fetches_nothing() = test {
        server.window = twoReminders()
        reminders.sync()
        // Started on the desktop a moment ago: neither of its times is due, and neither moved.
        server.answers["a"] = ReminderCheckResponse("a", due = false, notifyAt = t0 + 1.minutes, startsAt = t0 + 6.minutes)
        val before = fetches()

        clock = t0 + 6.minutes
        reminders.ringDue()

        assertEquals(emptyList(), scheduler.shown)
        delay(300)
        assertEquals(before, fetches(), "nothing moved: no new window")
    }

    @Test
    fun in_ten_minutes_it_rings_again_read_afresh_and_its_starts_now_waits_with_it() = test {
        val (early, _) = twoReminders()
        server.window = twoReminders()
        reminders.sync()
        clock = t0 + 1.minutes
        reminders.ringDue()

        reminders.snooze(early)
        assertEquals(listOf("a"), scheduler.scheduled, "only the reminder put off is ahead")
        clock = t0 + 6.minutes
        reminders.ringDue() // "starts now" falls inside the ten minutes: it waits with the one put off
        clock = t0 + 11.minutes
        reminders.ringDue()

        assertEquals(listOf("Starts at 12:15 — in 15 min", "Title a, again at ${t0 + 11.minutes}"), scheduler.bodies)
        assertEquals("a" to t0 + 1.minutes, server.checked.last(), "checked as the reminder it was")
        assertEquals(emptyList(), store.snoozed())
        assertEquals(emptyList(), scheduler.scheduled)
    }

    @Test
    fun a_reminder_put_off_reads_as_it_is_then() {
        val strings = app.kadans.i18n.Language.En.catalog.reminders
        val zone = kotlinx.datetime.TimeZone.UTC
        val putOff = reminder("a", t0 + 1.minutes, startsAt = t0 + 6.minutes)

        assertEquals("Starts at 12:06", LocalReminders.snoozedBody(putOff, t0 + 5.minutes, strings, zone))
        assertEquals("Started at 12:06", LocalReminders.snoozedBody(putOff, t0 + 11.minutes, strings, zone))
    }

    @Test
    fun done_or_started_from_its_notification_its_other_reminders_stay_quiet_offline_too() = test {
        server.window = twoReminders()
        reminders.sync()
        clock = t0 + 1.minutes
        reminders.ringDue()

        reminders.settled("a")
        server.reachable = false
        clock = t0 + 6.minutes
        reminders.ringDue()

        assertEquals(listOf("a"), scheduler.shown)
        assertEquals(emptyList(), scheduler.scheduled)
    }

    @Test
    fun start_focus_starts_the_todos_session_for_that_occurrence_and_quiets_its_starts_now() = test {
        val (early, _) = twoReminders(template = "tpl")
        server.window = twoReminders(template = "tpl")
        reminders.sync()
        val actions = ReminderActions(api, reminders, app.kadans.ui.pomodoro.PomodoroPreference(settings), DataChanges(emptyFlow()))

        val result = actions.startFocus(early)

        assertEquals("run-1", (result as ReminderActions.Result.FocusStarted).run?.id)
        val query = server.starts.single()
        assertTrue("occurrenceId=a" in query && "autoAdvance=true" in query && "loop=true" in query, query)
        assertEquals(emptyList(), scheduler.scheduled, "its reminders stay quiet here")
    }

    @Test
    fun a_session_already_running_counts_as_started_and_one_done_elsewhere_as_done() = test {
        val (early, _) = twoReminders(template = "tpl")
        server.window = twoReminders(template = "tpl")
        reminders.sync()
        val actions = ReminderActions(api, reminders, app.kadans.ui.pomodoro.PomodoroPreference(settings), DataChanges(emptyFlow()))

        server.startRefusedWith = "10030" // PomodoroAlreadyActiveForTodo
        assertEquals(ReminderActions.Result.FocusStarted(null), actions.startFocus(early))
        server.completeRefusedWith = "10005" // TaskAlreadyCompleted
        assertEquals(ReminderActions.Result.Done, actions.done(early))
        server.completeRefusedWith = "10020" // TaskAlreadyCancelled
        assertEquals(ReminderActions.Result.Done, actions.done(early))
    }

    @Test
    fun a_button_that_fails_leaves_the_reminders_ringing() = test {
        val (early, _) = twoReminders()
        server.window = twoReminders()
        reminders.sync()
        val actions = ReminderActions(api, reminders, app.kadans.ui.pomodoro.PomodoroPreference(settings), DataChanges(emptyFlow()))

        server.completeRefusedWith = "10012" // any other refusal
        assertEquals(ReminderActions.Result.Failed, actions.done(early))
        server.reachable = false
        assertEquals(ReminderActions.Result.Failed, actions.done(early))
        assertEquals(ReminderActions.Result.Failed, actions.startFocus(early))

        assertEquals(listOf("a", "a"), scheduler.scheduled, "both still to ring")
    }

    @Test
    fun a_button_that_worked_tells_the_screens_and_one_that_failed_does_not() = test {
        val (early, _) = twoReminders()
        server.window = twoReminders()
        reminders.sync()
        val changes = DataChanges(emptyFlow())
        val actions = ReminderActions(api, reminders, app.kadans.ui.pomodoro.PomodoroPreference(settings), changes)
        val heard = Channel<Set<DataArea>>(Channel.UNLIMITED)
        val listening = scope.launch { changes.changes.collect { heard.send(it) } }
        delay(100) // listening

        server.reachable = false
        assertEquals(ReminderActions.Result.Failed, actions.done(early))
        server.reachable = true
        assertEquals(ReminderActions.Result.Done, actions.done(early))

        // Only the one that worked: the todo's screen shows it done, not pending.
        assertEquals(setOf(DataArea.Todos), withTimeout(5_000) { heard.receive() })
        delay(100)
        assertTrue(heard.tryReceive().isFailure)
        listening.cancel()
    }
}
