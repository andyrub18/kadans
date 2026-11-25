package app.kadans.realtime

import app.kadans.notifications.showCycleNotice
import app.kadans.notifications.showSessionNotice
import app.kadans.notifications.showSystemNotification
import app.kadans.pomodoro.CycleNotice
import app.kadans.pomodoro.PhaseNotice
import app.kadans.reminders.LocalReminders
import app.kadans.sound.KadansSound
import app.kadans.sync.listen
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive

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
        // Heard from this call on (`listen`): a notification that arrives as the session starts is shown.
        collector = scope.listen(realtime.events) { event ->
            if (event is RealtimeEvent.NotificationReceived) {
                // A reminder, where this device shows reminders itself: once, whether its timer or the hub is first.
                val notification = event.notification
                val reminder = LocalReminders.reminderOf(notification)
                val cycle = CycleNotice.of(notification.kind, notification.data, notification.title, notification.body)
                val phase = PhaseNotice.of(notification.kind, notification.data, notification.title, notification.body)
                when {
                    reminder != null && localReminders.showsReminders() -> localReminders.pushed(reminder)
                    // A session's notice, where the device builds those itself (a phone): at once, ahead of its push.
                    phase != null && showSessionNotice(phase, PhaseNotice.isLive(notification.createdAt, Clock.System.now())) -> Unit
                    cycle != null -> showCycleNotice(cycle) // with "Another cycle" and "Done" where it can
                    else -> showSystemNotification(
                        notification.title,
                        notification.body,
                        KadansSound.of(notification.data?.get(KadansSound.DATA_KEY)),
                        notification.data?.get("link"),
                    )
                }
            }
        }
    }

    fun stop() {
        collector?.cancel()
        collector = null
    }
}
