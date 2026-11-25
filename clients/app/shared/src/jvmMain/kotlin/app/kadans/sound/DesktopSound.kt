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

    /**
     * A notice's own sound (a focus session's phase ending), unless the person turned sounds off: once, or again and
     * again until answered if they asked, as a reminder's.
     */
    fun notice(sound: KadansSound) = notice(sound, preferences()?.ring, player())

    /** [notice] with what it looks up handed in. */
    fun notice(sound: KadansSound, ring: RingChoice?, player: SoundPlayer?) {
        if (ring?.sounds == true) player?.play(sound, ring.keepRinging)
    }

    /** The person answered (a click on the notice, the app's window opened or touched): what still rings has been heard. */
    fun answered() {
        player()?.stop()
    }
}
