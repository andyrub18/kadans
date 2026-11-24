package app.kadans.sound

import com.russhwolf.settings.Settings
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Kadans' own sounds (ARCHITECTURE → "Sounds"; drawn by `branding/sounds.py`): a reminder, the end of a focus phase,
 * the end of a break. [id] is how a notification asks for one, in its data under [DATA_KEY] (the server's
 * `NotificationSounds`).
 */
enum class KadansSound(val id: String) {
    Reminder("reminder"),
    FocusOver("focus-over"),
    BreakOver("break-over"),
    ;

    companion object {
        const val DATA_KEY = "sound"

        fun of(id: String?): KadansSound? = entries.firstOrNull { it.id == id }
    }
}

/**
 * How a phone rings its reminders: like an alarm, heard even when the phone is on silent, or like a notification,
 * which follows the phone's own sound.
 */
enum class ReminderStyle { Alarm, Notification }

/** How reminders ring on this device, as the person set it: what the platform's side is handed when one rings. */
data class RingChoice(
    val style: ReminderStyle,
    /** A reminder rings again and again until it is answered, [SoundPreferences.RINGS_FOR] at most. */
    val keepRinging: Boolean,
    /** A computer plays Kadans' sounds itself, and can be told not to. A phone's sounds are the system's to turn off. */
    val sounds: Boolean,
)

/**
 * What the person chose about sounds, on this device: kept in the app's settings, and kept through a sign-out (they
 * are the device's, like the answer about "Alarms & reminders").
 */
class SoundPreferences(private val settings: Settings) {
    private val _style = MutableStateFlow(settings.getStringOrNull(STYLE)?.let { saved -> ReminderStyle.entries.firstOrNull { it.name == saved } })
    private val _keepRinging = MutableStateFlow(settings.getBoolean(KEEP_RINGING, false))
    private val _sounds = MutableStateFlow(settings.getBoolean(SOUNDS, true))

    /** The person's answer to "How should reminders reach you?"; null until they gave one. */
    val style: StateFlow<ReminderStyle?> = _style.asStateFlow()
    val keepRinging: StateFlow<Boolean> = _keepRinging.asStateFlow()
    val sounds: StateFlow<Boolean> = _sounds.asStateFlow()

    /**
     * How reminders ring now. Nobody rings like an alarm before having been asked: until the answer, a reminder
     * follows the phone's own sound, as it always did.
     */
    val ring: RingChoice get() = RingChoice(_style.value ?: ReminderStyle.Notification, _keepRinging.value, _sounds.value)

    fun choose(style: ReminderStyle) {
        settings.putString(STYLE, style.name)
        _style.value = style
    }

    fun setKeepRinging(on: Boolean) {
        settings.putBoolean(KEEP_RINGING, on)
        _keepRinging.value = on
    }

    fun setSounds(on: Boolean) {
        settings.putBoolean(SOUNDS, on)
        _sounds.value = on
    }

    companion object {
        /** The longest a reminder keeps ringing unanswered: a phone left at home does not ring for hours. */
        val RINGS_FOR: Duration = 1.minutes

        private const val STYLE = "kadans.sound.reminders"
        private const val KEEP_RINGING = "kadans.sound.keep-ringing"
        private const val SOUNDS = "kadans.sound.on"
    }
}

/**
 * Plays Kadans' sounds where the app does it itself: a computer, whose desktop shows a notification without a sound.
 * A phone's notifications carry their sound (their channel's), so there this does nothing.
 */
interface SoundPlayer {
    /** [sound] once; or, [keepRinging], again and again until [stop] or [SoundPreferences.RINGS_FOR]. */
    fun play(sound: KadansSound, keepRinging: Boolean = false)

    /** The person answered (a click, the window opened): whatever keeps ringing stops. */
    fun stop()
}

object NoSoundPlayer : SoundPlayer {
    override fun play(sound: KadansSound, keepRinging: Boolean) = Unit

    override fun stop() = Unit
}

expect fun platformSoundPlayer(): SoundPlayer

/** The phone's ringer, as its side buttons set it. */
enum class Ringer { Normal, Vibrate, Silent }

/**
 * What the phone's own settings say about a reminder's sound right now, read when Settings shows it. A phone lets the
 * person turn any of this, and an app can only read it and say so.
 */
data class SoundFacts(
    val notificationsOn: Boolean,
    /** The reminders' channel was set to silent, or to no sound, in the system's settings. */
    val channelSilent: Boolean,
    val ringer: Ringer,
    val alarmVolumeOff: Boolean,
)

/** What a reminder will do when it rings, in one sentence's worth: what Settings tells the person. */
enum class ReminderSoundState {
    /** Like an alarm: heard whatever the ringer says. */
    Alarm,

    /** Like an alarm, with the phone's alarm volume at zero: nothing will be heard. */
    AlarmVolumeOff,

    /** Like a notification, the phone's sound on. */
    Sound,
    PhoneSilent,
    PhoneVibrates,

    /** Silenced for Kadans' reminders in the system's settings. */
    Muted,
    NotificationsOff,
    ;

    companion object {
        fun of(style: ReminderStyle, facts: SoundFacts): ReminderSoundState = when {
            !facts.notificationsOn -> NotificationsOff
            facts.channelSilent -> Muted
            style == ReminderStyle.Alarm -> if (facts.alarmVolumeOff) AlarmVolumeOff else Alarm
            facts.ringer == Ringer.Silent -> PhoneSilent
            facts.ringer == Ringer.Vibrate -> PhoneVibrates
            else -> Sound
        }
    }
}
