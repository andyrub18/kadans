package app.kadans.pomodoro

import app.kadans.desktop.DesktopWindow
import app.kadans.notifications.FreedesktopNotifications
import app.kadans.notifications.notificationIcon
import app.kadans.notifications.showSystemNotification
import app.kadans.reminders.DesktopReminderNotices
import app.kadans.ui.focusLink
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * A looping session's cycle done on the desktop (ARCHITECTURE → Pomodoro). On Linux a freedesktop notification with
 * "Another cycle" and "Done" ([CycleActions]) and a click opening the session; one per session, the next cycle's taking
 * its place. A button whose request fails shows it again saying Kadans could not be reached, its buttons still there.
 * Elsewhere (or a desktop without a notification server) the system's own notification, without the buttons.
 */
internal class DesktopCycleNotices(
    private val linux: () -> FreedesktopNotifications? = { if (isLinux) DesktopReminderNotices.session else null },
    /** Looked up when shown: the app's wiring comes after this. */
    private val actions: () -> CycleActions? = { GlobalContext.getOrNull()?.getOrNull<CycleActions>() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    /** Each session's notice on screen. */
    private val shown = ConcurrentHashMap<String, Long>()

    fun show(notice: CycleNotice, body: String = notice.body) {
        val notifications = linux()
        val actions = actions()
        if (notifications?.clickable == true && actions != null) {
            val s = actions.strings
            val buttons = listOf(ANOTHER to s.pomodoro.anotherCycle, DONE to s.reminders.done)
            val id = notifications.show(notice.title, body, notificationIcon, buttons, replaces = shown[notice.runId] ?: 0) { key ->
                clicked(notice, key)
            }
            if (id != null) {
                shown[notice.runId] = id
                return
            }
        }
        showSystemNotification(notice.title, body)
    }

    private fun clicked(notice: CycleNotice, key: String) {
        when (key) {
            FreedesktopNotifications.DEFAULT -> DesktopWindow.open(focusLink(notice.todoId))
            ANOTHER, DONE -> scope.launch {
                val actions = actions() ?: return@launch
                val settled = if (key == ANOTHER) actions.anotherCycle(notice) else actions.done(notice)
                if (settled) shown.remove(notice.runId)
                else show(notice, "${notice.body}\n${actions.strings.reminders.actionFailed}")
            }
        }
    }

    internal companion object {
        const val ANOTHER = "another-cycle"
        const val DONE = "done"

        private val isLinux = "linux" in System.getProperty("os.name").lowercase()

        /** The app's, for its whole life. */
        val shared: DesktopCycleNotices by lazy { DesktopCycleNotices() }
    }
}
