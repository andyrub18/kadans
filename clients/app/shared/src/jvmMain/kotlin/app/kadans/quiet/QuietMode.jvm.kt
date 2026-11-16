package app.kadans.quiet

import app.kadans.api.model.ClockFormat
import app.kadans.i18n.StringsCatalog

// Do Not Disturb during focus is the phone's: a computer has no one switch an app may use.
actual fun platformQuietMode(catalog: () -> StringsCatalog, clock: () -> ClockFormat): QuietMode = NoQuietMode
