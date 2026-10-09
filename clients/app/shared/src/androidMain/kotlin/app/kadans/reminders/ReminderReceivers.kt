package app.kadans.reminders

import android.app.AlarmManager
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
