package app.kadans.quiet

import app.kadans.api.AuthTokens
import app.kadans.api.InMemoryTokenStore
import app.kadans.api.KadansApi
import app.kadans.api.KadansJson
import app.kadans.api.model.PomodoroPhaseType.Break
import app.kadans.api.model.PomodoroPhaseType.Focus
import app.kadans.api.model.PomodoroRunResponse
import app.kadans.api.model.PomodoroRunStatus
import app.kadans.push.DeviceRegistrar
import app.kadans.realtime.RealtimeEvent
import com.russhwolf.settings.MapSettings
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer

/**
 * Do Not Disturb during focus (ARCHITECTURE → "Do Not Disturb during focus"): when the phone goes quiet and gets its
 * sound back, what it does without the server, and whose word holds when the person turns the sound back on.
 */
class FocusQuietTests {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    /** The phone: what Android allows, what its mode was set to, and the alarm. */
    private class Phone(var access: QuietAccess = QuietAccess.Ready) : QuietMode {
        /** Every call: the phase the phone went quiet for, or null for the sound back. */
        val set = mutableListOf<QuietSpan?>()
        var wake: Instant? = null
        var opened = mutableListOf<QuietAccess>()
        var personTurnedOff = false
        var removed = 0

        val quiet: QuietSpan? get() = set.lastOrNull()

        override fun access() = access

        override fun openSettings(missing: QuietAccess) {
            opened += missing
        }

        override fun quiet(span: QuietSpan?) {
            set += span
            if (span == null) personTurnedOff = false // the phase over, Android forgets the person's "off"
        }

        override fun turnedOffByPerson() = personTurnedOff

        override fun wakeAt(at: Instant?) {
            wake = at
        }

        override fun remove() {
            removed++
        }
    }

    /** The server: the account's running sessions. */
    private inner class Server {
        var runs = listOf<PomodoroRunResponse>()
        var reachable = true
        val asked = mutableListOf<String>()
        val bodies = mutableListOf<String>()

        /** Runs while the list is on its way: news that reaches the phone in between. */
        var whileListing: suspend () -> Unit = {}
        val tokens = InMemoryTokenStore(AuthTokens("a", "r"))

        val api: KadansApi = KadansApi.create(
            "http://test",
            tokenStore = tokens,
            engine = MockEngine { request ->
                check(reachable) { "offline" }
                asked += "${request.method.value} ${request.url.encodedPath}"
                bodies += text(request)
                when {
                    request.url.encodedPath == "/pomodoro/active-runs" -> {
                        val listed = runs
                        whileListing()
                        respond(KadansJson.encodeToString(ListSerializer(PomodoroRunResponse.serializer()), listed), HttpStatusCode.OK, jsonHeaders)
                    }
                    request.url.encodedPath.startsWith("/pomodoro/runs/") ->
                        respond(KadansJson.encodeToString(PomodoroRunResponse.serializer(), runs.first()), HttpStatusCode.OK, jsonHeaders)
                    request.url.encodedPath.startsWith("/users/me/devices/") -> respond(
                        """{"installationId":"i","platform":"Android","name":"Phone","hasPushToken":false,"registeredAt":"2026-11-16T14:00:00Z","lastSeenAt":"2026-11-16T14:00:00Z"}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                    else -> respond("{}", HttpStatusCode.OK, jsonHeaders)
                }
            },
        )

        fun lists() = asked.count { it == "GET /pomodoro/active-runs" }

        private fun text(request: HttpRequestData) = (request.body as? TextContent)?.text.orEmpty()
    }

    private var clock = NINE
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var reports = 0
    private var reportSucceeds = true

    @AfterTest
    fun stop() = scope.cancel()

    /**
     * The service with its switch [on]. Unless [listening], it answers no signal by itself (its scope is already
     * over): a test then calls what it means.
     */
    private fun quiet(
        server: Server,
        phone: Phone,
        settings: MapSettings = MapSettings(),
        on: Boolean = true,
        events: MutableSharedFlow<RealtimeEvent> = MutableSharedFlow(),
        listening: Boolean = false,
    ): FocusQuiet {
        if (on) FocusQuietStore(settings).wanted = true
        return FocusQuiet(
            server.api,
            FocusQuietStore(settings),
            phone,
            events,
            report = {
                reports++
                reportSucceeds
            },
            now = { clock },
            scope = if (listening) scope else CoroutineScope(Job().also { it.cancel() }),
            checkWithin = 300.milliseconds,
        ).also { server.api.runSeen = it::see }
    }

    /** The alarm, in real time: it waits for the server a moment, which virtual time would skip. */
    private suspend fun FocusQuiet.alarm() = withContext(Dispatchers.Default) { withTimeout(3.seconds) { tick() } }

    private fun List<QuietSpan?>.untilMinutes() = map { it?.let { span -> (span.until - NINE).inWholeMinutes } }

    @Test
    fun the_phone_is_quiet_while_a_focus_phase_counts_down() = runTest {
        val server = Server().apply { runs = listOf(session()) }
        val phone = Phone()
        val quiet = quiet(server, phone)

        assertTrue(quiet.refresh())
        // Quiet until 9:25, the alarm two seconds before: the sound is back for what Kadans says at 9:25.
        assertEquals(NINE + 25.minutes, phone.quiet?.until)
        assertEquals("todo-run-1", phone.quiet?.todoId)
        assertEquals(NINE + 25.minutes - 2.seconds, phone.wake)

        // The alarm: the sound back, at once and without the server; the next alarm when focus begins again.
        clock = NINE + 25.minutes - 2.seconds
        server.reachable = false
        quiet.alarm()
        assertNull(phone.quiet)
        assertEquals(NINE + 30.minutes, phone.wake)
        assertEquals(1, server.lists())
    }

    @Test
    fun going_quiet_by_the_timetable_asks_the_server_first() = runTest {
        val server = Server().apply { runs = listOf(session()) }
        val phone = Phone()
        val quiet = quiet(server, phone)
        quiet.refresh()
        clock = NINE + 26.minutes
        quiet.alarm()
        assertNull(phone.quiet)

        // 9:30, the focus phase begins: the session was paused on the computer and the news never came.
        clock = NINE + 30.minutes
        server.runs = listOf(session(status = PomodoroRunStatus.Paused, endsAt = null, index = 1, updatedAt = NINE + 27.minutes))
        quiet.alarm()
        assertNull(phone.quiet)
        assertNull(phone.wake)
    }

    @Test
    fun offline_the_timetable_is_followed() = runTest {
        val server = Server().apply { runs = listOf(session()) }
        val phone = Phone()
        val quiet = quiet(server, phone)
        quiet.refresh()
        server.reachable = false

        clock = NINE + 26.minutes
        quiet.alarm()
        clock = NINE + 30.minutes
        quiet.alarm()
        // Quiet again from 9:30 to 9:55, then the long break, then nothing: the session waits for the person.
        assertEquals(NINE + 55.minutes, phone.quiet?.until)
        clock = NINE + 55.minutes - 2.seconds
        quiet.alarm()
        assertNull(phone.quiet)
        assertNull(phone.wake)
        assertEquals(listOf(25L, null, 55L, null), phone.set.untilMinutes())
    }

    @Test
    fun a_server_that_does_not_answer_in_time_does_not_hold_the_phone_back() = runTest {
        val server = Server().apply { runs = listOf(session()) }
        val phone = Phone()
        val quiet = quiet(server, phone)
        quiet.refresh()
        clock = NINE + 26.minutes
        quiet.alarm()

        clock = NINE + 30.minutes
        server.whileListing = { delay(5.seconds) }
        quiet.alarm()
        assertEquals(NINE + 55.minutes, phone.quiet?.until)
    }

    @Test
    fun what_this_device_does_to_a_session_settles_the_phone_without_another_request() = runTest {
        val server = Server().apply { runs = listOf(session()) }
        val phone = Phone()
        val quiet = quiet(server, phone)
        quiet.refresh()
        assertEquals(1, server.lists())

        // "Pause", pressed here: its answer is the news.
        clock = NINE + 10.minutes
        server.runs = listOf(session(status = PomodoroRunStatus.Paused, endsAt = null, updatedAt = clock))
        server.api.pomodoro.pause("run-1")
        assertNull(phone.quiet)
        assertNull(phone.wake)

        // "Resume": fifteen minutes were left.
        clock = NINE + 20.minutes
        server.runs = listOf(session(endsAt = clock + 15.minutes, updatedAt = clock))
        server.api.pomodoro.resume("run-1")
        assertEquals(NINE + 35.minutes, phone.quiet?.until)

        // "Finish".
        clock = NINE + 30.minutes
        server.runs = listOf(session(status = PomodoroRunStatus.Completed, endsAt = null, updatedAt = clock))
        server.api.pomodoro.finish("run-1")
        assertNull(phone.quiet)
        assertEquals(1, server.lists())
    }

    @Test
    fun news_older_than_what_is_held_is_not_believed() = runTest {
        val server = Server()
        val phone = Phone()
        val quiet = quiet(server, phone)

        clock = NINE + 10.minutes
        quiet.see(session(status = PomodoroRunStatus.Paused, endsAt = null, updatedAt = NINE + 10.minutes))
        // The hub's word of the start, late.
        quiet.see(session(updatedAt = NINE))
        assertNull(phone.quiet)

        // Over is over: "it runs" arriving after "it finished" changes nothing, whatever its date.
        quiet.see(session(status = PomodoroRunStatus.Completed, endsAt = null, updatedAt = NINE + 11.minutes))
        quiet.see(session(updatedAt = NINE + 12.minutes))
        assertNull(phone.quiet)
        assertEquals(emptyList(), phone.set)
    }

    @Test
    fun a_session_the_server_no_longer_lists_is_over() = runTest {
        val server = Server().apply { runs = listOf(session()) }
        val phone = Phone()
        val quiet = quiet(server, phone)
        quiet.refresh()
        assertEquals(NINE + 25.minutes, phone.quiet?.until)

        // Ended on the computer; a push says "changed".
        clock = NINE + 5.minutes
        server.runs = emptyList()
        quiet.refresh(show = true)
        assertNull(phone.quiet)
        assertNull(phone.wake)
    }

    @Test
    fun a_session_started_while_the_list_was_on_its_way_is_kept() = runTest {
        val server = Server()
        val phone = Phone()
        val quiet = quiet(server, phone)

        // The list was read with no session in it; before it arrives, "Start focus" is answered here.
        server.whileListing = {
            clock = NINE + 1.seconds
            quiet.see(session(updatedAt = NINE))
        }
        quiet.refresh()
        assertEquals(NINE + 25.minutes, phone.quiet?.until)
    }

    @Test
    fun sound_on_holds_for_the_rest_of_the_session_and_not_for_the_next() = runTest {
        val server = Server().apply { runs = listOf(session()) }
        val phone = Phone()
        val quiet = quiet(server, phone)
        quiet.refresh()

        clock = NINE + 5.minutes
        quiet.soundOn()
        assertNull(phone.quiet)
        assertNull(phone.wake)

        // Later news of the same session, the next focus phase included: the sound stays.
        clock = NINE + 30.minutes
        server.runs = listOf(session(index = 2, endsAt = NINE + 55.minutes, updatedAt = NINE + 30.minutes))
        quiet.refresh()
        assertNull(phone.quiet)

        // Another session is another matter.
        clock = NINE + 60.minutes
        server.runs = listOf(session(id = "run-2", endsAt = clock + 25.minutes, updatedAt = clock, startedAt = clock))
        quiet.refresh()
        assertEquals(clock + 25.minutes, phone.quiet?.until)
        assertEquals("todo-run-2", phone.quiet?.todoId)
    }

    @Test
    fun the_person_turning_the_mode_off_in_the_systems_settings_is_sound_on() = runTest {
        val server = Server().apply { runs = listOf(session()) }
        val phone = Phone()
        val quiet = quiet(server, phone)
        quiet.refresh()

        // Do Not Disturb turned off from the phone's quick settings, during the first focus phase.
        phone.personTurnedOff = true
        clock = NINE + 25.minutes - 2.seconds
        quiet.alarm()
        assertNull(phone.quiet)
        // The next focus phase of this session leaves the sound on: no alarm is even set for it.
        assertNull(phone.wake)
        clock = NINE + 30.minutes
        quiet.alarm()
        assertNull(phone.quiet)
    }

    @Test
    fun android_saying_the_mode_went_off_decides_nothing_by_itself() = runTest {
        val server = Server().apply { runs = listOf(session()) }
        val phone = Phone()
        val quiet = quiet(server, phone)
        quiet.refresh()
        server.reachable = false

        // The sound came back at the phase's end, by Kadans' own alarm: Android announces that too, and it means nothing.
        clock = NINE + 25.minutes - 2.seconds
        quiet.alarm()
        quiet.modeWentOff()
        clock = NINE + 30.minutes
        quiet.alarm()
        assertEquals(NINE + 55.minutes, phone.quiet?.until)

        // An announcement that comes late, when Kadans holds the phone quiet again and the mode is on: nothing either.
        clock = NINE + 35.minutes
        quiet.modeWentOff()
        assertEquals(NINE + 55.minutes, phone.quiet?.until)

        // Announced, and the phone rings for everything: the person turned it off. At once, not at the phase's end.
        phone.personTurnedOff = true
        clock = NINE + 40.minutes
        quiet.modeWentOff()
        assertNull(phone.quiet)
        assertNull(phone.wake)
    }

    @Test
    fun a_push_shows_the_notice_again_and_a_restart_sets_everything_again() = runTest {
        val server = Server().apply { runs = listOf(session()) }
        val phone = Phone()
        val settings = MapSettings()
        val quiet = quiet(server, phone, settings)
        quiet.refresh()
        assertEquals(1, phone.set.size)

        // Nothing changed: nothing is set again.
        quiet.refresh()
        assertEquals(1, phone.set.size)
        // A push: Firebase delivered it at once, the phone shows its notice.
        quiet.refresh(show = true)
        assertEquals(listOf(25L, 25L), phone.set.untilMinutes())

        // The phone restarted in the middle of the phase, offline: the system kept neither the alarm nor, maybe, the
        // mode. That is not the person turning it off.
        val after = Phone().apply { personTurnedOff = true }
        server.reachable = false
        clock = NINE + 10.minutes
        quiet(server, after, settings).restore()
        assertEquals(NINE + 25.minutes, after.quiet?.until)
        assertEquals(NINE + 25.minutes - 2.seconds, after.wake)
    }

    @Test
    fun a_phone_that_slept_through_the_end_gets_its_sound_back_when_it_wakes() = runTest {
        val server = Server().apply { runs = listOf(session(handsFree = false)) }
        val phone = Phone()
        val settings = MapSettings()
        quiet(server, phone, settings).refresh()
        assertEquals(NINE + 25.minutes, phone.quiet?.until)

        // Switched off at 9:10, back on at 11:00, offline.
        server.reachable = false
        clock = NINE + 120.minutes
        val after = Phone()
        quiet(server, after, settings).restore()
        assertEquals(listOf<QuietSpan?>(null, null), after.set)
        assertNull(after.wake)
    }

    @Test
    fun the_switch_off_follows_nothing() = runTest {
        val server = Server().apply { runs = listOf(session()) }
        val phone = Phone()
        val quiet = quiet(server, phone, on = false)

        assertEquals(QuietState.Off, quiet.state.value)
        assertFalse(quiet.refresh())
        quiet.see(session())
        assertEquals(0, server.lists())
        assertNull(phone.quiet)
        assertEquals(false, quiet.reportable)
    }

    @Test
    fun turning_it_on_asks_android_for_what_is_missing_one_thing_at_a_time() = runTest {
        val server = Server().apply { runs = listOf(session()) }
        val phone = Phone(QuietAccess.DoNotDisturbOff)
        val quiet = quiet(server, phone, on = false)
        assertTrue(quiet.offer.value)

        assertEquals(QuietAccess.DoNotDisturbOff, quiet.turnOn())
        assertEquals(listOf(QuietAccess.DoNotDisturbOff), phone.opened)
        assertEquals(QuietState.NeedsDoNotDisturbAccess, quiet.state.value)
        assertFalse(quiet.offer.value)
        assertEquals(false, quiet.reportable)
        assertNull(phone.quiet)

        // Back from the system's settings with it given; "Alarms & reminders" is still off.
        phone.access = QuietAccess.ExactAlarmsOff
        quiet.cameBack()
        assertEquals(QuietState.NeedsExactAlarms, quiet.state.value)
        quiet.openSettings()
        assertEquals(listOf(QuietAccess.DoNotDisturbOff, QuietAccess.ExactAlarmsOff), phone.opened)

        // Both given: the session running now is followed at once, and the server learns this phone goes quiet.
        phone.access = QuietAccess.Ready
        assertEquals(QuietAccess.Ready, quiet.turnOn())
        assertEquals(QuietState.On, quiet.state.value)
        assertEquals(NINE + 25.minutes, phone.quiet?.until)
        assertEquals(true, quiet.reportable)
        assertEquals(1, reports)
    }

    @Test
    fun turning_it_off_gives_the_sound_back_and_removes_the_mode() = runTest {
        val server = Server().apply { runs = listOf(session()) }
        val phone = Phone()
        val quiet = quiet(server, phone)
        quiet.refresh()
        assertEquals(1, reports)

        quiet.turnOff()
        assertNull(phone.quiet)
        assertNull(phone.wake)
        assertEquals(1, phone.removed)
        assertEquals(QuietState.Off, quiet.state.value)
        assertEquals(2, reports) // the server stops pushing session changes to this phone
        // The offer is not made to someone who used the switch.
        assertFalse(quiet.offer.value)
    }

    @Test
    fun the_person_removing_the_mode_in_the_systems_settings_is_the_switch_off() = runTest {
        val server = Server().apply { runs = listOf(session()) }
        val phone = Phone()
        val quiet = quiet(server, phone)
        quiet.refresh()

        // Android took the mode away with its access: the switch stays, and Settings says what is missing.
        phone.access = QuietAccess.DoNotDisturbOff
        quiet.modeRemoved()
        quiet.cameBack()
        assertEquals(QuietState.NeedsDoNotDisturbAccess, quiet.state.value)

        // The person removed it themselves.
        phone.access = QuietAccess.Ready
        quiet.modeRemoved()
        assertEquals(QuietState.Off, quiet.state.value)
        assertNull(phone.quiet)
    }

    @Test
    fun the_server_is_told_again_until_it_heard() = runTest {
        val server = Server()
        val phone = Phone()
        val quiet = quiet(server, phone)

        reportSucceeds = false
        quiet.refresh()
        quiet.refresh()
        assertEquals(2, reports)
        reportSucceeds = true
        quiet.refresh()
        quiet.refresh()
        assertEquals(3, reports) // heard: not told again while nothing changes
    }

    @Test
    fun signing_out_gives_the_sound_back_and_keeps_the_switch() = runTest {
        val server = Server().apply { runs = listOf(session()) }
        val phone = Phone()
        val settings = MapSettings()
        val quiet = quiet(server, phone, settings)
        quiet.refresh()
        assertEquals(NINE + 25.minutes, phone.quiet?.until)

        server.tokens.save(null)
        quiet.forget()
        assertNull(phone.quiet)
        assertNull(phone.wake)
        assertEquals(emptyMap(), FocusQuietStore(settings).known())
        // The switch is the phone's, like the access Android gave: the next account to sign in has it on.
        assertTrue(FocusQuietStore(settings).wanted)
        assertEquals(0, phone.removed)
        // Signed out, nothing is read.
        assertFalse(quiet.refresh())
        assertEquals(1, server.lists())
    }

    @Test
    fun what_a_fetch_begun_before_signing_out_brings_back_is_dropped() = runTest {
        val server = Server().apply { runs = listOf(session()) }
        val phone = Phone()
        val quiet = quiet(server, phone)

        server.whileListing = {
            server.tokens.save(null)
            quiet.forget()
        }
        assertFalse(quiet.refresh())
        assertNull(phone.quiet)
    }

    @Test
    fun behind_the_paywall_nothing_is_followed() = runTest {
        val server = Server().apply { runs = listOf(session()) }
        val phone = Phone()
        val quiet = quiet(server, phone)
        quiet.refresh()

        quiet.paywall(closed = true)
        assertNull(phone.quiet)
        assertFalse(quiet.refresh())
        quiet.see(session(updatedAt = NINE + 1.minutes))
        assertNull(phone.quiet)
    }

    @Test
    fun the_hub_brings_a_session_changed_elsewhere_while_the_app_is_open() = runTest {
        val server = Server()
        val phone = Phone()
        val events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 4)
        quiet(server, phone, events = events, listening = true)

        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                while (events.subscriptionCount.value == 0) delay(10)
                // Started on the computer.
                events.emit(RealtimeEvent.PomodoroRunChanged(session()))
                while (phone.quiet == null) delay(10)
                // The hub came back after a drop: the sessions are read again; this one ended meanwhile.
                events.emit(RealtimeEvent.Reconnected)
                while (phone.quiet != null) delay(10)
            }
        }
        assertEquals(1, server.lists())
    }

    @Test
    fun a_device_that_cannot_go_quiet_says_nothing_and_offers_nothing() = runTest {
        val server = Server().apply { runs = listOf(session()) }
        val quiet = FocusQuiet(server.api, FocusQuietStore(MapSettings()), NoQuietMode, MutableSharedFlow(), scope = CoroutineScope(Job().also { it.cancel() }))

        assertEquals(QuietState.Unsupported, quiet.state.value)
        assertFalse(quiet.offer.value)
        assertNull(quiet.reportable)
        assertFalse(quiet.refresh())
        assertEquals(0, server.lists())
    }

    @Test
    fun the_devices_registration_carries_whether_the_phone_goes_quiet() = runTest {
        val server = Server()
        var goesQuiet: Boolean? = true

        assertTrue(DeviceRegistrar(server.api, MapSettings(), quiet = { goesQuiet }).register())
        assertTrue(server.bodies.last().contains("\"quietDuringFocus\":true"), server.bodies.last())

        // A computer says nothing about it: the server keeps what it has.
        goesQuiet = null
        DeviceRegistrar(server.api, MapSettings(), quiet = { goesQuiet }).register()
        assertFalse(server.bodies.last().contains("quietDuringFocus"), server.bodies.last())

        server.reachable = false
        assertFalse(DeviceRegistrar(server.api, MapSettings(), quiet = { goesQuiet }).register())
    }

    @Test
    fun two_sessions_at_once_keep_the_phone_quiet_until_the_last_focus_phase_ends() = runTest {
        val long = session(id = "run-2", phases = listOf(Focus to 50, Break to 10), endsAt = NINE + 50.minutes)
        val server = Server().apply { runs = listOf(session(), long) }
        val phone = Phone()
        val quiet = quiet(server, phone)
        quiet.refresh()

        assertEquals(NINE + 50.minutes, phone.quiet?.until)
        // The long one's focus is over at 9:50; the other's second focus phase still counts down, until 9:55.
        clock = NINE + 50.minutes - 2.seconds
        server.reachable = false
        quiet.alarm()
        assertEquals(NINE + 55.minutes, phone.quiet?.until)
        assertEquals(NINE + 55.minutes - 2.seconds, phone.wake)
    }
}
