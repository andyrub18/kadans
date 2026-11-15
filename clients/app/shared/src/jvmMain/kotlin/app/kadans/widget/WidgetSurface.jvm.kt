package app.kadans.widget

import app.kadans.api.model.ClockFormat
import app.kadans.i18n.StringsCatalog

// A computer's desktop has no widgets: the app sits in the tray.
actual fun platformWidgetSurface(catalog: () -> StringsCatalog, clock: () -> ClockFormat): WidgetSurface = NoWidgetSurface
