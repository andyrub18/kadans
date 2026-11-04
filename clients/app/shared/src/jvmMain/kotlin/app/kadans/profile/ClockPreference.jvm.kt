package app.kadans.profile

import app.kadans.api.model.ClockFormat
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

/** The computer's region (LANG on Linux, the region on Windows): its short time has an AM/PM marker, or not. */
actual fun deviceClockFormat(): ClockFormat {
    val pattern = (DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()) as? SimpleDateFormat)?.toPattern()
    return if (pattern != null && 'a' in pattern) ClockFormat.TwelveHour else ClockFormat.TwentyFourHour
}
