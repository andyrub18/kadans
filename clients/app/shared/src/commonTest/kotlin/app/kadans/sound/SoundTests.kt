package app.kadans.sound

import app.kadans.TestMain
import app.kadans.api.KadansApi
import app.kadans.api.model.UpcomingReminder
import app.kadans.api.AuthTokens
import app.kadans.api.InMemoryTokenStore
import app.kadans.push.DeviceRegistrar
import app.kadans.reminders.ReminderAccess
import app.kadans.reminders.ReminderScheduler
import com.russhwolf.settings.MapSettings
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * How reminders sound (ARCHITECTURE → "Sounds"): what the person chose, what the phone's own settings make of it, and
 * what the server is told.
 */
class SoundTests {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun before() = TestMain.set(dispatcher)

    @AfterTest
    fun after() = TestMain.reset()

    private val soundOn = SoundFacts(notificationsOn = true, channelSilent = false, ringer = Ringer.Normal, alarmVolumeOff = false)

    @Test
    fun an_alarm_is_heard_whatever_the_ringer_says_and_a_notification_follows_it() {
        for (ringer in Ringer.entries) {
            assertEquals(ReminderSoundState.Alarm, ReminderSoundState.of(ReminderStyle.Alarm, soundOn.copy(ringer = ringer)))
        }
        assertEquals(ReminderSoundState.Sound, ReminderSoundState.of(ReminderStyle.Notification, soundOn))
        assertEquals(ReminderSoundState.PhoneSilent, ReminderSoundState.of(ReminderStyle.Notification, soundOn.copy(ringer = Ringer.Silent)))
        assertEquals(ReminderSoundState.PhoneVibrates, ReminderSoundState.of(ReminderStyle.Notification, soundOn.copy(ringer = Ringer.Vibrate)))
    }

    @Test
    fun what_the_person_turned_off_in_the_phones_settings_is_said_first() {
        // An alarm with the alarm volume at zero is heard by nobody: said, not "rings like an alarm".
        assertEquals(ReminderSoundState.AlarmVolumeOff, ReminderSoundState.of(ReminderStyle.Alarm, soundOn.copy(alarmVolumeOff = true)))
        // The alarm volume means nothing to a reminder that rings like a notification.
        assertEquals(ReminderSoundState.Sound, ReminderSoundState.of(ReminderStyle.Notification, soundOn.copy(alarmVolumeOff = true)))
        // The reminders silenced for Kadans, or every notification off: no way of ringing gets past that.
        for (style in ReminderStyle.entries) {
            assertEquals(ReminderSoundState.Muted, ReminderSoundState.of(style, soundOn.copy(channelSilent = true)))
            assertEquals(ReminderSoundState.NotificationsOff, ReminderSoundState.of(style, soundOn.copy(notificationsOn = false, channelSilent = true)))
        }
    }

    @Test
    fun nobody_rings_like_an_alarm_before_having_said_so() {
        val settings = MapSettings()
        val preferences = SoundPreferences(settings)

        assertNull(preferences.style.value)
        assertEquals(RingChoice(ReminderStyle.Notification, keepRinging = false, sounds = true), preferences.ring)

        preferences.choose(ReminderStyle.Alarm)
        preferences.setKeepRinging(true)
        preferences.setSounds(false)
        assertEquals(RingChoice(ReminderStyle.Alarm, keepRinging = true, sounds = false), preferences.ring)

        // Kept on the device: the app started again reads the same.
        assertEquals(preferences.ring, SoundPreferences(settings).ring)
        assertEquals(ReminderStyle.Alarm, SoundPreferences(settings).style.value)
    }

    @Test
    fun a_notification_names_its_sound_and_an_unknown_one_is_none() {
        assertEquals(KadansSound.FocusOver, KadansSound.of("focus-over"))
        assertEquals(KadansSound.BreakOver, KadansSound.of("break-over"))
        assertEquals(KadansSound.Reminder, KadansSound.of("reminder"))
        assertNull(KadansSound.of("fanfare"))
        assertNull(KadansSound.of(null))
    }

    /** A phone ([facts] given) or a computer (none): what the system's side was told. */
    private class FakeScheduler(private val facts: SoundFacts?) : ReminderScheduler {
        var changes = 0
        var tests = 0
        var opened = 0

        override fun access() = ReminderAccess.Ready
        override fun openSettings(missing: ReminderAccess) = Unit
        override fun schedule(reminders: List<UpcomingReminder>) = Unit
        override fun show(reminder: UpcomingReminder) = Unit
        override fun keepFresh(enabled: Boolean) = Unit
        override fun soundFacts() = facts
        override fun ringChanged() { changes++ }
        override fun ringTest() { tests++ }
        override fun openSoundSettings() { opened++ }
    }

    private class Rig(facts: SoundFacts?, scope: kotlinx.coroutines.CoroutineScope) {
        val registered = mutableListOf<String>()
        private val settings = MapSettings().apply { putString("kadans.installation", "11111111-1111-1111-1111-111111111111") }
        private val api = KadansApi.create(
            "http://test",
            InMemoryTokenStore(AuthTokens("a", "r")),
            MockEngine { request ->
                registered += request.body.toByteArray().decodeToString()
                respond(
                    """{"installationId":"11111111-1111-1111-1111-111111111111","platform":"Android","name":"Phone","hasPushToken":false,"registeredAt":"2026-11-24T12:00:00Z","lastSeenAt":"2026-11-24T12:00:00Z"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val scheduler = FakeScheduler(facts)
        val preferences = SoundPreferences(settings)
        lateinit var ring: RingSettings
        val registrar = DeviceRegistrar(api, settings, alarmReminders = { ring.reportable })

        init {
            ring = RingSettings(preferences, scheduler, registrar, scope)
        }

        suspend fun awaitRegistrations(count: Int) =
            withContext(Dispatchers.Default) { withTimeout(5_000) { while (registered.size < count) delay(10) } }
    }

    @Test
    fun the_question_is_for_a_phone_until_answered_and_once_a_run_when_closed() = runTest(dispatcher) {
        val phone = Rig(soundOn, this)
        assertTrue(phone.ring.shouldAsk())
        phone.ring.askLater()
        assertFalse(phone.ring.shouldAsk(), "closed without an answer: not at every screen")
        // Another run of the app: still unanswered, asked again.
        assertTrue(RingSettings(phone.preferences, phone.scheduler, phone.registrar, this).shouldAsk())

        phone.ring.choose(ReminderStyle.Notification)
        testScheduler.runCurrent()
        phone.awaitRegistrations(1)
        assertFalse(RingSettings(phone.preferences, phone.scheduler, phone.registrar, this).shouldAsk(), "answered, either way")

        // A computer's reminders are not the system's to ring: nothing to ask.
        assertFalse(Rig(null, this).ring.shouldAsk())
    }

    @Test
    fun a_choice_reaches_the_phones_channels_and_the_server() = runTest(dispatcher) {
        val phone = Rig(soundOn, this)
        assertEquals(ReminderSoundState.Sound, phone.ring.state())

        phone.ring.choose(ReminderStyle.Alarm)
        testScheduler.runCurrent()
        phone.awaitRegistrations(1)

        assertEquals(1, phone.scheduler.changes, "the channel reminders go in follows")
        assertTrue("\"alarmReminders\":true" in phone.registered.single(), phone.registered.single())
        assertEquals(ReminderSoundState.Alarm, phone.ring.state())

        phone.ring.choose(ReminderStyle.Notification)
        testScheduler.runCurrent()
        phone.awaitRegistrations(2)
        assertTrue("\"alarmReminders\":false" in phone.registered.last(), phone.registered.last())
    }

    @Test
    fun a_phone_that_has_not_answered_is_registered_as_ringing_like_a_notification_and_a_computer_says_nothing() = runTest(dispatcher) {
        val phone = Rig(soundOn, this)
        assertEquals(false, phone.ring.reportable)
        phone.registrar.register()
        assertTrue("\"alarmReminders\":false" in phone.registered.single(), phone.registered.single())

        val computer = Rig(null, this)
        assertNull(computer.ring.reportable)
        assertNull(computer.ring.state())
        computer.registrar.register()
        assertFalse("alarmReminders" in computer.registered.single(), computer.registered.single())
    }

    @Test
    fun settings_reaches_the_systems_page_and_the_test() = runTest(dispatcher) {
        val phone = Rig(soundOn, this)
        phone.ring.openSystemSettings()
        phone.ring.test()
        phone.ring.setKeepRinging(true)

        assertEquals(1, phone.scheduler.opened)
        assertEquals(1, phone.scheduler.tests)
        assertTrue(phone.preferences.ring.keepRinging)
        assertEquals(0, phone.scheduler.changes, "ringing until answered changes no channel")
    }
}
