package app.kadans.profile

import app.kadans.api.model.ClockFormat
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The clock this device shows: "9:00 PM" where it is set to 12 hours, "21:00" where it is set to 24. */
expect fun deviceClockFormat(): ClockFormat

/**
 * How this person reads the time, "21:00" or "9:00 PM": the account's choice (Settings), kept on the device so every
 * screen shows it at once, offline too. The account is the truth: [ProfileSync] adopts it at each start. Until an
 * account has been seen here, the device's own clock, which is also what a new account starts with.
 */
class ClockPreference(private val settings: Settings) {
    private val _format = MutableStateFlow(stored(settings) ?: deviceClockFormat())
    val format: StateFlow<ClockFormat> = _format.asStateFlow()

    /** The account's choice, as its profile says (each start, and after a change in Settings). */
    fun adopt(format: ClockFormat) {
        settings.putString(KEY, format.name)
        _format.value = format
    }

    companion object {
        private const val KEY = "kadans.clock"

        /** For what runs away from the screens (a reminder rung again): the same choice, read directly. */
        fun current(settings: Settings): ClockFormat = stored(settings) ?: deviceClockFormat()

        private fun stored(settings: Settings): ClockFormat? =
            settings.getStringOrNull(KEY)?.let { name -> ClockFormat.entries.firstOrNull { it.name == name } }
    }
}
