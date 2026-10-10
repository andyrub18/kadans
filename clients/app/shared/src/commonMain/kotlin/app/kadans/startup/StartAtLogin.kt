package app.kadans.startup

import com.russhwolf.settings.Settings

/**
 * Opening Kadans when the person signs in to the computer, in the background, so reminders ring after a restart
 * without opening it (ARCHITECTURE → "The desktop app: in the tray, once"). Desktop only, and only for an installed
 * app: a dev run has no launcher a session could start. Elsewhere [available] is false and Settings shows nothing.
 * Phones need none of it: Android puts the next reminder's alarm back after a reboot by itself.
 */
interface StartAtLogin {
    val available: Boolean

    /** What the system will do at the next sign-in, read afresh: the person may have changed it in the system's settings. */
    fun isEnabled(): Boolean

    fun setEnabled(enabled: Boolean)

    companion object {
        /** The argument a session's start carries: no window, the tray only. */
        const val BACKGROUND = "--background"
        internal const val DECIDED_KEY = "kadans.startAtLogin.decided"
    }
}

/** Phones, a Mac until its build, and dev runs: nothing a session could start. */
object NoStartAtLogin : StartAtLogin {
    override val available = false

    override fun isEnabled() = false

    override fun setEnabled(enabled: Boolean) = Unit
}

expect fun platformStartAtLogin(): StartAtLogin

/**
 * At each launch. The first launch of an installed app turns it on: a reminder app whose reminders stop at a restart
 * would fail at its one job (Settings turns it off). Later launches rewrite an entry still there, for the app may have
 * moved, and leave one the person removed, in Settings or in the system's own, removed.
 */
fun StartAtLogin.onLaunch(settings: Settings) {
    if (!available) return
    if (!settings.getBoolean(StartAtLogin.DECIDED_KEY, false)) {
        setEnabled(true)
        settings.putBoolean(StartAtLogin.DECIDED_KEY, true)
    } else if (isEnabled()) {
        setEnabled(true)
    }
}
