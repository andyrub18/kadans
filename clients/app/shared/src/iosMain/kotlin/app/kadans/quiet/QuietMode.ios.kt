package app.kadans.quiet

import app.kadans.api.model.ClockFormat
import app.kadans.i18n.StringsCatalog

// Apple lets no app switch a Focus on: nothing to offer on the iPhone.
actual fun platformQuietMode(catalog: () -> StringsCatalog, clock: () -> ClockFormat): QuietMode = NoQuietMode
