package app.kadans.profile

import android.text.format.DateFormat
import app.kadans.api.model.ClockFormat
import app.kadans.config.AndroidAppContext

/** Settings → System → Date & time → "Use 24-hour format", which follows the region until someone sets it. */
actual fun deviceClockFormat(): ClockFormat =
    if (DateFormat.is24HourFormat(AndroidAppContext.context)) ClockFormat.TwentyFourHour else ClockFormat.TwelveHour
