package app.kadans.sound

import app.kadans.push.DeviceRegistrar
import app.kadans.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The person's choices about how reminders ring, made to take effect (ARCHITECTURE → "Sounds"): kept on the device
 * ([SoundPreferences]), handed to the system (a phone's channel), and told to the server, which names the channel of a
 * reminder the system shows without the app. Settings and the question asked once both go through here.
 */
class RingSettings(
    private val preferences: SoundPreferences,
    private val scheduler: ReminderScheduler,
    private val registrar: DeviceRegistrar,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    val style: StateFlow<ReminderStyle?> get() = preferences.style
    val keepRinging: StateFlow<Boolean> get() = preferences.keepRinging
    val sounds: StateFlow<Boolean> get() = preferences.sounds

    /** A reminder's sound here is the system's (a phone's notification), not one the app plays (a computer). */
    val onAPhone: Boolean get() = scheduler.soundFacts() != null

    /** What a reminder will do when it rings, as the phone's own settings stand now; null on a computer. */
    fun state(): ReminderSoundState? = scheduler.soundFacts()?.let { ReminderSoundState.of(preferences.ring.style, it) }

    /**
     * What the server is told when this device registers: true or false on a phone, as reminders ring now (like a
     * notification until the person has answered), and nothing from a computer.
     */
    val reportable: Boolean? get() = if (onAPhone) preferences.ring.style == ReminderStyle.Alarm else null

    private var dismissed = false

    /**
     * Whether to ask "How should reminders reach you?" now: on a phone, until answered, and once a run at most for
     * someone who closes the question without answering.
     */
    fun shouldAsk(): Boolean = !dismissed && preferences.style.value == null && onAPhone

    /** The question was closed without an answer: asked again another day, not at every screen. */
    fun askLater() {
        dismissed = true
    }

    fun choose(style: ReminderStyle) {
        preferences.choose(style)
        scheduler.ringChanged()
        scope.launch { registrar.register() }
    }

    fun setKeepRinging(on: Boolean) = preferences.setKeepRinging(on)

    fun setSounds(on: Boolean) = preferences.setSounds(on)

    /** The system's own page for the reminders' sound. */
    fun openSystemSettings() = scheduler.openSoundSettings()

    fun test() = scheduler.ringTest()
}
