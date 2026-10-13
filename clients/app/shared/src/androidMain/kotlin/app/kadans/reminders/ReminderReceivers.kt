package app.kadans.reminders

import android.app.AlarmManager
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.kadans.api.KadansJson
import app.kadans.api.model.UpcomingReminder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext

/** The next reminder's alarm rang: [LocalReminders.ringDue] shows what is due and sets the following alarm. */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        // No overall limit: its server check gives up after two seconds, and cut short it could ring nothing.
        work { it.ringDue() }
    }

    internal companion object {
        const val ACTION = "app.kadans.reminders.RING"
    }
}

/**
 * A reminder's button ([ReminderActions]): "Start focus" or "Done" asks the server, within the time a receiver has,
 * "In 10 min" puts it off on this phone. The notification then shows what happened: the session's notice, gone, or the
 * reminder again saying Kadans could not be reached, its buttons still there.
 */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminder = intent.getStringExtra(REMINDER)
            ?.let { runCatching { KadansJson.decodeFromString(UpcomingReminder.serializer(), it) }.getOrNull() }
            ?: return
        val koin = GlobalContext.getOrNull() ?: return
        val notices = koin.get<ReminderScheduler>() as? AndroidReminderScheduler ?: return
        val actions = koin.get<ReminderActions>()
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    START_FOCUS -> when (withTimeoutOrNull(WITHIN) { actions.startFocus(reminder) }) {
                        is ReminderActions.Result.FocusStarted -> notices.showFocusStarted(reminder)
                        else -> notices.showFailed(reminder)
                    }
                    DONE -> when (withTimeoutOrNull(WITHIN) { actions.done(reminder) }) {
                        ReminderActions.Result.Done -> notices.dismiss(reminder)
                        else -> notices.showFailed(reminder)
                    }
                    SNOOZE -> {
                        actions.snooze(reminder)
                        notices.dismiss(reminder)
                    }
                }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }

    internal companion object {
        const val START_FOCUS = "app.kadans.reminders.START_FOCUS"
        const val DONE = "app.kadans.reminders.DONE"
        const val SNOOZE = "app.kadans.reminders.SNOOZE"
        const val REMINDER = "reminder"

        /** Inside the ten seconds a receiver has: the server's answer, or "could not be reached". */
        val WITHIN = 8.seconds
    }
}

/**
 * A reboot, an app update, or "Alarms & reminders" just granted: the OS rings nothing until the stored window is
 * scheduled again ([LocalReminders.restore]), and the window is fetched afresh.
 */
class ReminderRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> work { withTimeoutOrNull(RESTORE_WITHIN) { it.restore() } }
        }
    }

    private companion object {
        /** Inside the ten seconds a receiver has; the next sync finishes whatever the fetch did not. */
        val RESTORE_WITHIN = 8.seconds
    }
}

/**
 * Twice a day, with a network, while this phone rings reminders: the window is fetched again. The server trusts a
 * window for 36 hours, which leaves room for Android putting the job off.
 */
class ReminderSyncJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        val reminders = GlobalContext.getOrNull()?.get<LocalReminders>() ?: return false
        scope.launch {
            try {
                reminders.sync()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Never a crash from the background: the next period, or the app's next start, tries again.
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    /** The network went: the next period tries again. */
    override fun onStopJob(params: JobParameters): Boolean {
        scope.coroutineContext.cancelChildren()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    internal companion object {
        const val ID = 4_221_001
        val EVERY = 12.hours
    }
}

/**
 * The receiver's work, off the main thread, keeping the process alive until it is done ([goAsync]). A failure is
 * dropped, never a crash: the next sync (the app's start, the twice-daily job) sets things right.
 */
private fun BroadcastReceiver.work(block: suspend (LocalReminders) -> Unit) {
    val reminders = GlobalContext.getOrNull()?.get<LocalReminders>() ?: return
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        try {
            block(reminders)
        } catch (_: Exception) {
        } finally {
            pending.finish()
        }
    }
}
