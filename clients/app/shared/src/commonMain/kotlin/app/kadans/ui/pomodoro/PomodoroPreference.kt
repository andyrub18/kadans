package app.kadans.ui.pomodoro

import com.russhwolf.settings.Settings

/**
 * Per install: does a new Pomodoro session run hands-free (the server moves to the next phase and notifies)? On by
 * default: most people start a session and look away. Turned off, each phase waits at 0:00 for "Next phase", with
 * one "time's up" notification. The last choice made on this device is the next default.
 */
class PomodoroPreference(private val settings: Settings) {
    var handsFree: Boolean
        get() = settings.getBoolean(KEY, true)
        set(value) = settings.putBoolean(KEY, value)

    private companion object {
        const val KEY = "kadans.pomodoro.handsFree"
    }
}
