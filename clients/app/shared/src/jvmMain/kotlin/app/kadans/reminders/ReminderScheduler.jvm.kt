package app.kadans.reminders

import app.kadans.api.model.UpcomingReminder
import app.kadans.notifications.showSystemNotification
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

actual fun platformReminderScheduler(channelName: () -> String, onDue: suspend () -> Unit): ReminderScheduler =
    DesktopReminderScheduler(onDue)

/**
 * The desktop app's reminders (ARCHITECTURE → "Reminders ring on the phone"): a timer in the app, for the next reminder,
 * which keeps running while the window is closed to the tray. It rings offline as on a phone; it does not ring while
 * the app is not running (the hub has nothing to deliver to then either), and the stored window is scheduled again
 * when the app starts ([restoreDesktopReminders]). Shown as the desktop's other notifications are.
 *
 * No background job: while the app runs, the hub keeps the window current (each change, each reconnect, at least
 * hourly when the access token is renewed).
 */
internal class DesktopReminderScheduler(
    private val onDue: suspend () -> Unit,
    private val now: () -> Instant = { Clock.System.now() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ReminderScheduler {
    private var timer: Job? = null

    override fun access() = ReminderAccess.Ready

    override fun openSettings(missing: ReminderAccess) = Unit

    @Synchronized
    override fun schedule(reminders: List<UpcomingReminder>) {
        timer?.cancel()
        val next = reminders.minOfOrNull { it.notifyAt } ?: return
        timer = scope.launch {
            // In short steps on the wall clock: after the computer slept, or its clock was set, it still rings on time.
            while (true) {
                val wait = next - now()
                if (wait <= Duration.ZERO) break
                delay(minOf(wait, STEP))
            }
            // Its own job: ringing schedules the next timer, which cancels this one.
            scope.launch { onDue() }
        }
    }

    override fun show(reminder: UpcomingReminder) = showSystemNotification(reminder.title, reminder.body)

    override fun keepFresh(enabled: Boolean) = Unit

    internal companion object {
        /** The longest the timer sleeps at once: how late a reminder can be after the computer wakes. */
        val STEP = 30.seconds
    }
}

/** The desktop app starts: its timers died with the last run, so the stored window rings again, and is fetched afresh. */
fun restoreDesktopReminders() {
    GlobalContext.getOrNull()?.get<LocalReminders>()?.restoreSoon()
}
