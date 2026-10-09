package app.kadans.reminders

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.kadans.api.model.UpcomingReminder
import app.kadans.auth.AndroidActivityHolder
import app.kadans.config.AndroidAppContext
import app.kadans.ui.todoLink
import app.kadans.shared.R
import app.kadans.ui.brand.KadansMark

// The alarm reaches LocalReminders through ReminderAlarmReceiver, in whatever process Android starts for it.
actual fun platformReminderScheduler(channelName: () -> String, onDue: suspend () -> Unit): ReminderScheduler =
    AndroidReminderScheduler(AndroidAppContext.context, channelName)

/**
 * Exact alarms, one at a time: the next reminder's ([ReminderAlarmReceiver] rings it and sets the one after). One alarm
 * stays far below Android's 500 per app, and what rings is read from the stored window when it rings. Alarms are
 * `setExactAndAllowWhileIdle`: on time in Doze, which needs "Alarms & reminders" from Android 12 on (off by default for
 * new installs since Android 14). Reminders have their own channel, urgent (heads-up, sound), listed under
 * [channelName] in the system settings.
 */
internal class AndroidReminderScheduler(private val context: Context, private val channelName: () -> String) : ReminderScheduler {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val jobs = context.getSystemService(JobScheduler::class.java)

    override fun access(): ReminderAccess = when {
        !notifications.areNotificationsEnabled() -> ReminderAccess.NotificationsOff
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarms.canScheduleExactAlarms() -> ReminderAccess.ExactAlarmsOff
        else -> ReminderAccess.Ready
    }

    override fun openSettings(missing: ReminderAccess) {
        val screen = when {
            missing == ReminderAccess.ExactAlarmsOff && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri())
            missing == ReminderAccess.NotificationsOff && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            missing == ReminderAccess.NotificationsOff -> appDetails()
            else -> return
        }
        // Some makers leave out the dedicated screens: the app's own page has the same switches.
        if (!open(screen)) open(appDetails())
    }

    override fun schedule(reminders: List<UpcomingReminder>) {
        val next = reminders.minByOrNull { it.notifyAt }
        if (next == null) {
            alarms.cancel(ring())
            return
        }
        try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.notifyAt.toEpochMilliseconds(), ring())
        } catch (_: SecurityException) {
            // The permission went between the check and here: the next sync hands the reminders back to the push.
        }
    }

    override fun show(reminder: UpcomingReminder) {
        ensureChannel()
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, CHANNEL_ID)
            else @Suppress("DEPRECATION") Notification.Builder(context).setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL)
        val notification = builder
            .setSmallIcon(R.drawable.ic_stat_kadans)
            .setColor(KadansMark.PURPLE_ARGB)
            .setContentTitle(reminder.title)
            .setContentText(reminder.body)
            .setStyle(Notification.BigTextStyle().bigText(reminder.body))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setWhen(reminder.notifyAt.toEpochMilliseconds())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(openTodo(reminder))
            .build()
        // Tagged with the occurrence, as Firebase tags the push it shows (id 0): one replaces the other, never both.
        runCatching { notifications.notify(reminder.occurrenceId, 0, notification) }
    }

    override fun keepFresh(enabled: Boolean) {
        if (!enabled) {
            jobs.cancel(ReminderSyncJob.ID)
            return
        }
        if (jobs.getPendingJob(ReminderSyncJob.ID) != null) return // scheduling it again would restart its clock
        val job = JobInfo.Builder(ReminderSyncJob.ID, ComponentName(context, ReminderSyncJob::class.java))
            .setPeriodic(ReminderSyncJob.EVERY.inWholeMilliseconds)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true) // across reboots (RECEIVE_BOOT_COMPLETED)
            .build()
        runCatching { jobs.schedule(job) }
    }

    /** The channel, named in the app's language (a later call renames it); there from app start, so it can be tuned early. */
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_ID, channelName(), NotificationManager.IMPORTANCE_HIGH))
    }

    private fun ring(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ReminderAlarmReceiver::class.java).setAction(ReminderAlarmReceiver.ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The app, on this reminder's todo: brought forward if it is open (onNewIntent), started if not. */
    private fun openTodo(reminder: UpcomingReminder): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(todoLink(reminder.todoId)))
            .setComponent(context.packageManager.getLaunchIntentForPackage(context.packageName)?.component)
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun packageUri() = Uri.parse("package:${context.packageName}")

    private fun appDetails() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri())

    /** From the screen in front when there is one (the system's screen comes back to it), else as a task of its own. */
    private fun open(intent: Intent): Boolean = runCatching {
        val activity = AndroidActivityHolder.get()
        if (activity != null) activity.startActivity(intent) else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    companion object {
        const val CHANNEL_ID = "kadans.reminders"
    }
}
