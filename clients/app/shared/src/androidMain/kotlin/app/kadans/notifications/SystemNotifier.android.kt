package app.kadans.notifications

import app.kadans.config.AndroidAppContext
import app.kadans.pomodoro.CycleActions
import app.kadans.pomodoro.PhaseNotice
import app.kadans.pomodoro.SessionNotices
import app.kadans.sound.SoundPreferences
import org.koin.core.context.GlobalContext

// A phone's notifications come by push (FCM) and from its own alarms; the in-app snackbar covers the foreground.
actual fun showSystemNotification(title: String, body: String, sound: app.kadans.sound.KadansSound?, link: String?) = Unit

/**
 * A session's notice heard on the live connection: built here at once, as its push will be when it arrives, a second
 * or more later on a phone's network, to be dropped then (`SessionNotices.showOnce`). That wait was the lag between a
 * countdown reaching zero on the screen and its sound. One that is not [live] is left to the push, which showed it at
 * the time.
 */
actual fun showSessionNotice(notice: PhaseNotice, live: Boolean): Boolean {
    if (live) {
        val koin = GlobalContext.getOrNull()
        val keepRinging = koin?.getOrNull<SoundPreferences>()?.ring?.keepRinging == true
        runCatching { SessionNotices.showOnce(AndroidAppContext.context, notice, koin?.getOrNull<CycleActions>(), keepRinging) }
    }
    return true
}

// The push shows it, with its buttons (SessionNotices): the app's connection does not run in the background.
actual fun showCycleNotice(notice: app.kadans.pomodoro.CycleNotice) = Unit
