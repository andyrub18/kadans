package app.kadans.profile

import com.russhwolf.settings.Settings

/**
 * Per install: does this device keep the account's time zone in line with its own? On by default. Off on a
 * device that should not (a laptop left on another zone than the phone), or once the person picks a zone.
 */
class TimeZonePreference(private val settings: Settings) {
    var followsDevice: Boolean
        get() = settings.getBoolean(KEY, true)
        set(value) = settings.putBoolean(KEY, value)

    private companion object {
        const val KEY = "kadans.timezone.followDevice"
    }
}
