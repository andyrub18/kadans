package app.kadans.sound

import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

actual fun platformSoundPlayer(): SoundPlayer = DesktopSoundPlayer()

/** One of Kadans' sounds, opened and ready to be heard. */
internal interface Voice {
    fun once()

    /** Again and again, until [stop]. */
    fun loop()

    fun stop()
}

/**
 * The desktop app plays Kadans' sounds itself (ARCHITECTURE → "Sounds"): a desktop shows a notification without a
 * sound, on Linux always and on Windows as its settings say, and a beep is nobody's idea of a reminder. One sound of
 * its own, the same on every computer, from the app's resources.
 *
 * A reminder that keeps ringing is one at a time (a later one takes its place) and stops when the person answers
 * ([stop]) or after [ringsFor]. A sound played once never cuts a ring short. A computer without a sound card, or whose
 * sound cannot be opened, stays silent: nothing here ever fails the reminder it accompanies.
 */
internal class DesktopSoundPlayer(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val ringsFor: Duration = SoundPreferences.RINGS_FOR,
    private val voice: (KadansSound) -> Voice? = ::clipOf,
) : SoundPlayer {
    private val lock = Any()
    private var ringing: Voice? = null
    private var limit: Job? = null

    override fun play(sound: KadansSound, keepRinging: Boolean) {
        // Never on the caller's thread: opening the sound card can take a moment, and the caller may be the window's.
        scope.launch {
            val opened = voice(sound) ?: return@launch
            if (!keepRinging) {
                opened.once()
                return@launch
            }
            synchronized(lock) {
                quiet()
                ringing = opened
                opened.loop()
                limit = scope.launch {
                    delay(ringsFor)
                    synchronized(lock) { if (ringing === opened) quiet() }
                }
            }
        }
    }

    override fun stop() = synchronized(lock) { quiet() }

    /** Under [lock]. */
    private fun quiet() {
        limit?.cancel()
        limit = null
        ringing?.stop()
        ringing = null
    }

    internal companion object {
        /** A sound's file in the app's resources (written by `branding/sounds.py`). */
        fun resource(sound: KadansSound): String = "/sounds/${sound.id}.wav"

        /** The file as audio: its format read, nothing played. Null when it is missing. */
        fun audioOf(sound: KadansSound): AudioInputStream? {
            val bytes = DesktopSoundPlayer::class.java.getResourceAsStream(resource(sound))?.use { it.readBytes() } ?: return null
            return AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes))
        }

        private fun clipOf(sound: KadansSound): Voice? = runCatching {
            val audio = audioOf(sound) ?: return null
            val clip = AudioSystem.getClip()
            clip.open(audio)
            ClipVoice(clip)
        }.getOrNull()
    }
}

/** A sound on the computer's own output, through Java's sound. Its line is given back as soon as it has been heard. */
private class ClipVoice(private val clip: Clip) : Voice {
    override fun once() {
        clip.addLineListener { event -> if (event.type == LineEvent.Type.STOP) runCatching { clip.close() } }
        runCatching { clip.start() }
    }

    override fun loop() {
        runCatching { clip.loop(Clip.LOOP_CONTINUOUSLY) }
    }

    override fun stop() {
        runCatching {
            clip.stop()
            clip.close()
        }
    }
}
