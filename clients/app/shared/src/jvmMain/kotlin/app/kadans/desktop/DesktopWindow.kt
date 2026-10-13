package app.kadans.desktop

import app.kadans.ui.IncomingLinks
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The desktop app's window, asked for from outside it: a reminder's notification clicked. The app's `main` says how to
 * bring the window forward ([show]); the link then opens in it, over where the person was.
 */
object DesktopWindow {
    @Volatile
    var show: () -> Unit = {}

    fun open(link: String) {
        show()
        IncomingLinks.open(link)
    }
}

/**
 * Windows and macOS show a reminder as a balloon of the app's tray icon, and a click on it reaches only that icon's
 * action, as a double-click on the icon does: an "open" within [CLICKABLE] of a reminder's balloon is taken as that
 * click, and opens its link. Later, or without one, the window just shows.
 */
object ReminderBalloon {
    private val CLICKABLE = 1.minutes
    private var last: Pair<String, Instant>? = null

    @Synchronized
    fun shown(link: String, at: Instant = Clock.System.now()) {
        last = link to at
    }

    /** The link of a balloon shown just before [now], once. */
    @Synchronized
    fun takeClick(now: Instant = Clock.System.now()): String? {
        val (link, at) = last ?: return null
        last = null
        return link.takeIf { now - at <= CLICKABLE }
    }
}
