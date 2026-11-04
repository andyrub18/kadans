package app.kadans.profile

import app.kadans.api.model.ClockFormat
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale

/** Settings → General → Date & Time → "24-Hour Time": the locale's hour template has an AM/PM marker, or not. */
actual fun deviceClockFormat(): ClockFormat {
    val template = NSDateFormatter.dateFormatFromTemplate("j", 0uL, NSLocale.currentLocale) ?: return ClockFormat.TwentyFourHour
    return if ('a' in template) ClockFormat.TwelveHour else ClockFormat.TwentyFourHour
}
