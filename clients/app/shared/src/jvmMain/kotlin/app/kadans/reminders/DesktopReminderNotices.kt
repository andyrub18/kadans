package app.kadans.reminders

import app.kadans.api.model.UpcomingReminder
import app.kadans.desktop.DesktopWindow
import app.kadans.desktop.ReminderBalloon
import app.kadans.i18n.ReminderStrings
import app.kadans.notifications.FreedesktopNotifications
import app.kadans.notifications.notificationIcon
import app.kadans.notifications.showDesktopNotification
import app.kadans.sound.KadansSound
import app.kadans.sound.ReminderStyle
import app.kadans.sound.RingChoice
import app.kadans.sound.SoundPlayer
import app.kadans.ui.focusLink
import app.kadans.ui.todoLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * The desktop's reminders as notifications (ARCHITECTURE → "Reminders ring on the phone"). On Linux, freedesktop
 * notifications with the reminder's buttons, as on Android ([ReminderActions]: "Start focus" or "Done", "In 10 min";
 * none on the heads-up before the start, [UpcomingReminder.isHeadsUp]), and a click opening its todo; the result shows as the phone's does: the session's notice, nothing, or the reminder
 * again saying Kadans could not be reached. Elsewhere (or a desktop without a notification server) the system's own
 * notification without buttons: on Windows and macOS a balloon, whose click opens the todo ([ReminderBalloon]).
 */
internal class DesktopReminderNotices(
    private val strings: () -> ReminderStrings,
    /** How the person wants reminders to ring here, and what plays the sound (ARCHITECTURE → "Sounds"). */
    private val ring: () -> RingChoice = { RingChoice(ReminderStyle.Notification, keepRinging = false, sounds = false) },
    private val player: () -> SoundPlayer? = { GlobalContext.getOrNull()?.getOrNull<SoundPlayer>() },
    private val linux: () -> FreedesktopNotifications? = { if (isLinux) session else null },
    /** Looked up when a button is pressed: the actions need the reminders, which need this. */
    private val actions: () -> ReminderActions? = { GlobalContext.getOrNull()?.getOrNull<ReminderActions>() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    /** [ringing] false: the reminder shown again after a button (it could not be reached), without its sound. */
    fun show(reminder: UpcomingReminder, body: String = reminder.body, ringing: Boolean = true) {
        // The desktop shows a notification without a sound: Kadans plays its own, and keeps on if the person asked.
        val choice = ring()
        if (ringing && choice.sounds) player()?.play(KadansSound.Reminder, choice.keepRinging)
        val notifications = linux()
        if (notifications?.clickable == true) {
            val s = strings()
            val first = if (reminder.isFocus) START to s.startFocus else DONE to s.done
            val buttons = if (reminder.isHeadsUp) emptyList() else listOf(first, SNOOZE to s.snooze)
            val shown = notifications.show(reminder.title, body, notificationIcon, buttons, onDismissed = { player()?.stop() }) { key ->
                clicked(reminder, key)
            }
            if (shown != null) return
        }
        // Without a sound of its own: the reminder's was played above.
        showDesktopNotification(reminder.title, body)
        if (!isLinux) ReminderBalloon.shown(todoLink(reminder.todoId))
    }

    private fun clicked(reminder: UpcomingReminder, key: String) {
        // Any answer is an answer: a reminder that keeps ringing stops.
        player()?.stop()
        when (key) {
            FreedesktopNotifications.DEFAULT -> DesktopWindow.open(todoLink(reminder.todoId))
            START -> scope.launch {
                when (actions()?.startFocus(reminder)) {
                    is ReminderActions.Result.FocusStarted -> focusStarted(reminder)
                    else -> failed(reminder)
                }
            }
            DONE -> scope.launch { if (actions()?.done(reminder) != ReminderActions.Result.Done) failed(reminder) }
            SNOOZE -> scope.launch { actions()?.snooze(reminder) }
        }
    }

    /** The session runs: its notice, a click on which opens it. */
    private fun focusStarted(reminder: UpcomingReminder) {
        linux()?.show(strings().focusStarted, reminder.title, notificationIcon) { key ->
            if (key == FreedesktopNotifications.DEFAULT) DesktopWindow.open(focusLink(reminder.todoId))
        }
    }

    private fun failed(reminder: UpcomingReminder) = show(reminder, "${reminder.body}\n${strings().actionFailed}", ringing = false)

    internal companion object {
        const val START = "start-focus"
        const val DONE = "done"
        const val SNOOZE = "snooze"

        private val isLinux = "linux" in System.getProperty("os.name").lowercase()

        /**
         * One connection to the session's notification server for the app's life, made at the first reminder or cycle
         * notice (`DesktopCycleNotices` shares it).
         */
        val session: FreedesktopNotifications? by lazy { FreedesktopNotifications.connect() }
    }
}
