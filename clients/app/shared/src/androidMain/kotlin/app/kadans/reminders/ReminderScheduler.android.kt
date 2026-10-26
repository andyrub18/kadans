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
import android.graphics.drawable.Icon
import app.kadans.api.KadansJson
import app.kadans.api.model.UpcomingReminder
import app.kadans.i18n.ReminderStrings
import app.kadans.ui.focusLink
import kotlin.time.Duration.Companion.minutes
import app.kadans.auth.AndroidActivityHolder
import app.kadans.config.AndroidAppContext
import app.kadans.ui.todoLink
import app.kadans.shared.R
import app.kadans.ui.brand.KadansMark

// The alarm reaches LocalReminders through ReminderAlarmReceiver, in whatever process Android starts for it.
actual fun platformReminderScheduler(strings: () -> ReminderStrings, onDue: suspend () -> Unit): ReminderScheduler =
    AndroidReminderScheduler(AndroidAppContext.context, strings)

/**
 * Exact alarms, one at a time: the next reminder's ([ReminderAlarmReceiver] rings it and sets the one after). One alarm
 * stays far below Android's 500 per app, and what rings is read from the stored window when it rings. Alarms are
 * `setExactAndAllowWhileIdle`: on time in Doze, which needs "Alarms & reminders" from Android 12 on (off by default for
 * new installs since Android 14). Reminders have their own channel, urgent (heads-up, sound), listed under the
 * [strings]' channel name in the system settings, and two buttons ([ReminderActionReceiver]).
 */
internal class AndroidReminderScheduler(private val context: Context, private val strings: () -> ReminderStrings) : ReminderScheduler {
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

    override fun show(reminder: UpcomingReminder) = post(reminder, reminder(reminder, reminder.body))

    /** "Start focus" worked: the reminder becomes the session's notice, silently (a tap opens the session). */
    fun showFocusStarted(reminder: UpcomingReminder) {
        val notice = builder()
            .setOnlyAlertOnce(true)
            .setContentTitle(strings().focusStarted)
            .setContentText(reminder.title)
            .setAutoCancel(true)
            .setContentIntent(openLink(focusLink(reminder.todoId)))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) notice.setTimeoutAfter(FOCUS_NOTICE.inWholeMilliseconds)
        post(reminder, notice.build())
    }

    /** A button's request failed (offline, signed out): the reminder stays, saying so, with its buttons. */
    fun showFailed(reminder: UpcomingReminder) =
        post(reminder, reminder(reminder, "${reminder.body}\n${strings().actionFailed}", alert = false))

    /** Done, or put off: the reminder goes. */
    fun dismiss(reminder: UpcomingReminder) {
        runCatching { notifications.cancel(reminder.occurrenceId, 0) }
    }

    /**
     * The reminder's notification: its words, a tap opening its todo, and its buttons, but for the heads-up before the
     * start ([UpcomingReminder.isHeadsUp]). [alert] false: updated silently.
     */
    private fun reminder(reminder: UpcomingReminder, body: String, alert: Boolean = true): Notification {
        val s = strings()
        val first = if (reminder.isFocus) ReminderActionReceiver.START_FOCUS to s.startFocus else ReminderActionReceiver.DONE to s.done
        val notification = builder()
            .setOnlyAlertOnce(!alert)
            .setContentTitle(reminder.title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setWhen(reminder.notifyAt.toEpochMilliseconds())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(openLink(todoLink(reminder.todoId)))
        if (!reminder.isHeadsUp) {
            notification
                .addAction(button(reminder, first.first, first.second))
                .addAction(button(reminder, ReminderActionReceiver.SNOOZE, s.snooze))
        }
        return notification.build()
    }

    private fun builder(): Notification.Builder {
        ensureChannel()
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, CHANNEL_ID)
            else @Suppress("DEPRECATION") Notification.Builder(context).setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL)
        return builder.setSmallIcon(R.drawable.ic_stat_kadans).setColor(KadansMark.PURPLE_ARGB)
    }

    /** Tagged with the occurrence, as Firebase tags the push it shows (id 0): one replaces the other, never both. */
    private fun post(reminder: UpcomingReminder, notification: Notification) {
        runCatching { notifications.notify(reminder.occurrenceId, 0, notification) }
    }

    /**
     * One button: a broadcast to [ReminderActionReceiver] (not exported), carrying the reminder. Its own data URI per
     * reminder and button, as extras are not part of a PendingIntent's identity: two notifications never share one.
     */
    private fun button(reminder: UpcomingReminder, action: String, label: String): Notification.Action {
        val intent = Intent(context, ReminderActionReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("kadans://reminders/${reminder.occurrenceId}/${reminder.notifyAt.toEpochMilliseconds()}/$action"))
            .putExtra(ReminderActionReceiver.REMINDER, KadansJson.encodeToString(UpcomingReminder.serializer(), reminder))
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_kadans), label, pending).build()
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
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_ID, strings().channelName, NotificationManager.IMPORTANCE_HIGH))
    }

    private fun ring(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ReminderAlarmReceiver::class.java).setAction(ReminderAlarmReceiver.ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The app, on this link (the reminder's todo, its focus session): brought forward if it is open (onNewIntent), started if not. */
    private fun openLink(link: String): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
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

        /** How long "Focus session started" stays: the session's own notices follow. */
        val FOCUS_NOTICE = 10.minutes
    }
}
