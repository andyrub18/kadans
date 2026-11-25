package app.kadans.sound

import javax.sound.sampled.AudioFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The desktop app plays Kadans' sounds itself (ARCHITECTURE → "Sounds"): once, or again and again until the person
 * answers, a minute at most. The sound card is stood in for; the files are the real ones.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopSoundTests {
    /** What each sound was asked to do, in order. */
    private class Voices {
        val heard = mutableListOf<String>()

        fun of(sound: KadansSound): Voice = object : Voice {
            override fun once() { heard += "${sound.id} once" }
            override fun loop() { heard += "${sound.id} loop" }
            override fun stop() { heard += "${sound.id} stop" }
        }
    }

    @Test
    fun a_sound_is_played_once_unless_asked_to_keep_ringing() = runTest {
        val voices = Voices()
        val player = DesktopSoundPlayer(backgroundScope, ringsFor = 60.seconds, voice = voices::of)

        player.play(KadansSound.FocusOver)
        runCurrent()
        assertEquals(listOf("focus-over once"), voices.heard)

        advanceTimeBy(120.seconds)
        runCurrent()
        assertEquals(listOf("focus-over once"), voices.heard, "nothing to stop: it ended by itself")
    }

    @Test
    fun a_phases_end_keeps_ringing_if_the_person_asked_and_says_nothing_with_sounds_off() = runTest {
        val voices = Voices()
        val player = DesktopSoundPlayer(backgroundScope, ringsFor = 60.seconds, voice = voices::of)
        fun ring(keepRinging: Boolean, sounds: Boolean = true) = RingChoice(ReminderStyle.Notification, keepRinging, sounds)

        DesktopSound.notice(KadansSound.FocusOver, ring(keepRinging = false), player)
        runCurrent()
        assertEquals(listOf("focus-over once"), voices.heard)

        // "Keep ringing until I answer" is a phase's end too, not reminders alone.
        DesktopSound.notice(KadansSound.BreakOver, ring(keepRinging = true), player)
        runCurrent()
        assertEquals(listOf("focus-over once", "break-over loop"), voices.heard)
        player.stop() // a click on the notice, the window in front or touched
        assertEquals(listOf("focus-over once", "break-over loop", "break-over stop"), voices.heard)

        DesktopSound.notice(KadansSound.FocusOver, ring(keepRinging = true, sounds = false), player)
        DesktopSound.notice(KadansSound.FocusOver, ring = null, player = player) // before the app is wired
        runCurrent()
        assertEquals(3, voices.heard.size, "sounds turned off: nothing more")
    }

    @Test
    fun a_reminder_keeps_ringing_until_answered() = runTest {
        val voices = Voices()
        val player = DesktopSoundPlayer(backgroundScope, ringsFor = 60.seconds, voice = voices::of)

        player.play(KadansSound.Reminder, keepRinging = true)
        runCurrent()
        advanceTimeBy(20.seconds)
        assertEquals(listOf("reminder loop"), voices.heard)

        player.stop()
        assertEquals(listOf("reminder loop", "reminder stop"), voices.heard)

        // Answered: the minute's limit has nothing left to stop.
        advanceTimeBy(120.seconds)
        runCurrent()
        assertEquals(listOf("reminder loop", "reminder stop"), voices.heard)
    }

    @Test
    fun unanswered_it_stops_after_a_minute() = runTest {
        val voices = Voices()
        val player = DesktopSoundPlayer(backgroundScope, ringsFor = 60.seconds, voice = voices::of)

        player.play(KadansSound.Reminder, keepRinging = true)
        runCurrent()
        advanceTimeBy(59.seconds)
        assertEquals(listOf("reminder loop"), voices.heard)
        advanceTimeBy(2.seconds)
        runCurrent()

        assertEquals(listOf("reminder loop", "reminder stop"), voices.heard)
    }

    @Test
    fun a_later_reminder_takes_the_place_of_one_still_ringing_and_a_phase_sound_does_not_cut_it_short() = runTest {
        val voices = Voices()
        val player = DesktopSoundPlayer(backgroundScope, ringsFor = 60.seconds, voice = voices::of)

        player.play(KadansSound.Reminder, keepRinging = true)
        runCurrent()
        player.play(KadansSound.BreakOver)
        runCurrent()
        assertEquals(listOf("reminder loop", "break-over once"), voices.heard)

        player.play(KadansSound.Reminder, keepRinging = true)
        runCurrent()
        assertEquals(listOf("reminder loop", "break-over once", "reminder stop", "reminder loop"), voices.heard)

        // The first one's minute does not end the second one's ring.
        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(4, voices.heard.size)
    }

    @Test
    fun a_computer_whose_sound_cannot_be_opened_stays_silent() = runTest {
        val player = DesktopSoundPlayer(backgroundScope, ringsFor = 60.seconds, voice = { null })

        player.play(KadansSound.Reminder, keepRinging = true)
        player.play(KadansSound.FocusOver)
        runCurrent()
        player.stop() // nothing rings, nothing fails
    }

    @Test
    fun every_sound_ships_with_the_app_as_audio_java_can_play() {
        for (sound in KadansSound.entries) {
            val audio = assertNotNull(DesktopSoundPlayer.audioOf(sound), "missing: ${DesktopSoundPlayer.resource(sound)} (branding/sounds.py)")
            val format = audio.format
            assertEquals(AudioFormat.Encoding.PCM_SIGNED, format.encoding, sound.id)
            assertEquals(44100f, format.sampleRate, sound.id)
            assertEquals(16, format.sampleSizeInBits, sound.id)
            assertEquals(1, format.channels, sound.id)
            val seconds = audio.frameLength / format.frameRate
            assertTrue(seconds in 1f..3f, "${sound.id} lasts $seconds s: a notification's sound, not a tune")
        }
    }
}
