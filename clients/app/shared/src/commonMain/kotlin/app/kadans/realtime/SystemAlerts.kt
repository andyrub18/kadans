package app.kadans.realtime

import app.kadans.notifications.showCycleNotice
import app.kadans.notifications.showSystemNotification
import app.kadans.pomodoro.CycleNotice
import app.kadans.reminders.LocalReminders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Turns every pushed notification (phase changes, reminders — already localized server-side)
 * into an OS-level notification, so a minimized window still tells the user a break or focus
 * period began. Lives for the whole signed-in session, independent of any screen.
 */
class SystemAlerts(private val realtime: KadansRealtime, private val localReminders: LocalReminders) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collector: Job? = null

    fun start() {
        if (collector?.isActive == true) return
        collector = scope.launch {
            realtime.events.collect { event ->
                if (event is RealtimeEvent.NotificationReceived) {
                    // A reminder, where this device shows reminders itself: once, whether its timer or the hub is first.
                    val notification = event.notification
                    val reminder = LocalReminders.reminderOf(notification)
                    val cycle = CycleNotice.of(notification.kind, notification.data, notification.title, notification.body)
                    when {
                        reminder != null && localReminders.showsReminders() -> localReminders.pushed(reminder)
                        cycle != null -> showCycleNotice(cycle) // with "Another cycle" and "Done" where it can
                        else -> showSystemNotification(notification.title, notification.body)
                    }
                }
            }
        }
    }

    fun stop() {
        collector?.cancel()
        collector = null
    }
}
