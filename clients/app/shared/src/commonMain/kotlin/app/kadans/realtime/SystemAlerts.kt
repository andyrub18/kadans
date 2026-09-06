package app.kadans.realtime

import app.kadans.notifications.showSystemNotification
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
class SystemAlerts(private val realtime: KadansRealtime) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collector: Job? = null

    fun start() {
        if (collector?.isActive == true) return
        collector = scope.launch {
            realtime.events.collect { event ->
                if (event is RealtimeEvent.NotificationReceived) {
                    showSystemNotification(event.notification.title, event.notification.body)
                }
            }
        }
    }

    fun stop() {
        collector?.cancel()
        collector = null
    }
}
