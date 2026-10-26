package app.kadans.reminders

import app.kadans.api.model.UpcomingReminder
import app.kadans.desktop.DesktopWindow
import app.kadans.desktop.ReminderBalloon
import app.kadans.i18n.ReminderStrings
import app.kadans.notifications.FreedesktopNotifications
import app.kadans.notifications.notificationIcon
import app.kadans.notifications.showSystemNotification
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
    private val linux: () -> FreedesktopNotifications? = { if (isLinux) session else null },
    /** Looked up when a button is pressed: the actions need the reminders, which need this. */
    private val actions: () -> ReminderActions? = { GlobalContext.getOrNull()?.getOrNull<ReminderActions>() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    fun show(reminder: UpcomingReminder, body: String = reminder.body) {
        val notifications = linux()
        if (notifications?.clickable == true) {
            val s = strings()
            val first = if (reminder.isFocus) START to s.startFocus else DONE to s.done
            val buttons = if (reminder.isHeadsUp) emptyList() else listOf(first, SNOOZE to s.snooze)
            val shown = notifications.show(reminder.title, body, notificationIcon, buttons) { key ->
                clicked(reminder, key)
            }
            if (shown != null) return
        }
        showSystemNotification(reminder.title, body)
        if (!isLinux) ReminderBalloon.shown(todoLink(reminder.todoId))
    }

    private fun clicked(reminder: UpcomingReminder, key: String) {
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

    private fun failed(reminder: UpcomingReminder) = show(reminder, "${reminder.body}\n${strings().actionFailed}")

    internal companion object {
        const val START = "start-focus"
        const val DONE = "done"
        const val SNOOZE = "snooze"

        private val isLinux = "linux" in System.getProperty("os.name").lowercase()

        /** One connection to the session's notification server for the app's life, made at the first reminder. */
        private val session: FreedesktopNotifications? by lazy { FreedesktopNotifications.connect() }
    }
}
