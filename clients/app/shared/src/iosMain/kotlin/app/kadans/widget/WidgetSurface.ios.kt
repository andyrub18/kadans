package app.kadans.widget

import app.kadans.api.model.ClockFormat
import app.kadans.i18n.StringsCatalog

// The iPhone's widget (WidgetKit) comes with the iPhone app.
actual fun platformWidgetSurface(catalog: () -> StringsCatalog, clock: () -> ClockFormat): WidgetSurface = NoWidgetSurface
