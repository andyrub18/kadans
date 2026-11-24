package app.kadans.sound

import org.koin.core.context.GlobalContext

/**
 * The desktop app's sounds, from the places that show a notification without being handed anything (ARCHITECTURE →
 * "Sounds"): looked up when one is shown, since the app's wiring comes after them. Before the wiring, and in a test
 * without it, nothing is played.
 */
internal object DesktopSound {
    private fun preferences(): SoundPreferences? = GlobalContext.getOrNull()?.getOrNull<SoundPreferences>()

    private fun player(): SoundPlayer? = GlobalContext.getOrNull()?.getOrNull<SoundPlayer>()

    /** A notice's own sound, once, unless the person turned sounds off. */
    fun notice(sound: KadansSound) {
        if (preferences()?.ring?.sounds == true) player()?.play(sound)
    }

    /** The person came to the app (its window opened): a reminder still ringing has been heard. */
    fun answered() {
        player()?.stop()
    }
}
