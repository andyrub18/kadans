package app.kadans.reminders

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.graphics.drawable.Icon
import app.kadans.api.KadansJson
import app.kadans.api.model.UpcomingReminder
import app.kadans.i18n.ReminderStrings
import kotlin.time.Duration.Companion.seconds
import app.kadans.sound.KadansChannels
import app.kadans.sound.KadansSound
import app.kadans.sound.ReminderStyle
import app.kadans.sound.RingChoice
import app.kadans.sound.Ringing
import app.kadans.sound.Ringer
import app.kadans.sound.SoundFacts
import app.kadans.auth.AndroidActivityHolder
import app.kadans.config.AndroidAppContext
import app.kadans.ui.todoLink
import app.kadans.shared.R
import app.kadans.ui.brand.KadansMark

// The alarm reaches LocalReminders through ReminderAlarmReceiver, in whatever process Android starts for it.
actual fun platformReminderScheduler(strings: () -> ReminderStrings, ring: () -> RingChoice, onDue: suspend () -> Unit): ReminderScheduler =
    AndroidReminderScheduler(AndroidAppContext.context, strings, ring)

/**
 * Exact alarms, one at a time: the next reminder's ([ReminderAlarmReceiver] rings it and sets the one after). One alarm
 * stays far below Android's 500 per app, and what rings is read from the stored window when it rings. Alarms are
 * `setExactAndAllowWhileIdle`: on time in Doze, which needs "Alarms & reminders" from Android 12 on (off by default for
 * new installs since Android 14). Reminders have their own channel, urgent (heads-up, sound), listed under the
 * [strings]' channel name in the system settings, and two buttons ([ReminderActionReceiver]).
 *
 * How one rings is the person's choice ([choice]; ARCHITECTURE → "Sounds"): in the channel that rings like an alarm or
 * in the one that rings like a notification ([KadansChannels]), once or, if they asked, again and again until
 * answered, a minute at most ([Ringing]).
 */
internal class AndroidReminderScheduler(
    private val context: Context,
    private val strings: () -> ReminderStrings,
    private val choice: () -> RingChoice = { RingChoice(ReminderStyle.Notification, keepRinging = false, sounds = true) },
) : ReminderScheduler {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val jobs = context.getSystemService(JobScheduler::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)

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
            // A few seconds ahead: the app starts and asks the server, and the reminder rings at its second (ringDue).
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, (next.notifyAt - LocalReminders.AHEAD).toEpochMilliseconds(), ring())
        } catch (_: SecurityException) {
            // The permission went between the check and here: the next sync hands the reminders back to the push.
        }
    }

    override fun show(reminder: UpcomingReminder) =
        Ringing.post(context, reminder.occurrenceId, reminder(reminder, reminder.body).build(), choice().keepRinging)

    /** A button's request failed (offline, signed out): the reminder is back, saying so, with its buttons and no sound. */
    fun showFailed(reminder: UpcomingReminder) = with(Ringing) {
        postQuietly(context, reminder.occurrenceId, reminder(reminder, "${reminder.body}\n${strings().actionFailed}").quietly().build())
    }

    /** Answered (a button pressed): the reminder goes, and with it its ringing. */
    fun dismiss(reminder: UpcomingReminder) {
        runCatching { notifications.cancel(reminder.occurrenceId, 0) }
    }

    /**
     * The reminder's notification: its words, a tap opening its todo, and its buttons, but for the heads-up before the
     * start ([UpcomingReminder.isHeadsUp]). Tagged with the occurrence, as Firebase tags the push it shows (id 0): one
     * replaces the other, never both.
     */
    private fun reminder(reminder: UpcomingReminder, body: String): Notification.Builder {
        val s = strings()
        val first = if (reminder.isFocus) ReminderActionReceiver.START_FOCUS to s.startFocus else ReminderActionReceiver.DONE to s.done
        val notification = builder()
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
        return notification
    }

    /** In the channel that rings as the person chose; before Android 8, which has no channels, with the same sound. */
    private fun builder(): Notification.Builder {
        ensureChannels()
        val style = choice().style
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, KadansChannels.reminders(style))
            else @Suppress("DEPRECATION") Notification.Builder(context)
                .setPriority(Notification.PRIORITY_HIGH)
                .setSound(KadansChannels.uri(context, KadansSound.Reminder), KadansChannels.attributes(style == ReminderStyle.Alarm))
                .setVibrate(KadansChannels.VIBRATION)
        return builder.setSmallIcon(R.drawable.ic_stat_kadans).setColor(KadansMark.PURPLE_ARGB)
    }

    /** A broadcast about [reminder] to [ReminderActionReceiver]: its own data URI, so that two never share one. */
    private fun actionIntent(reminder: UpcomingReminder, action: String): Intent =
        Intent(context, ReminderActionReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("kadans://reminders/${reminder.occurrenceId}/${reminder.notifyAt.toEpochMilliseconds()}/$action"))
            .putExtra(ReminderActionReceiver.REMINDER, KadansJson.encodeToString(UpcomingReminder.serializer(), reminder))

    /**
     * One button: a broadcast to [ReminderActionReceiver] (not exported), carrying the reminder. Its own data URI per
     * reminder and button, as extras are not part of a PendingIntent's identity: two notifications never share one.
     */
    private fun button(reminder: UpcomingReminder, action: String, label: String): Notification.Action {
        val pending = PendingIntent.getBroadcast(
            context, 0, actionIntent(reminder, action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
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

    /** The channels, named in the app's language (a later call renames them); there from app start, so they can be tuned early. */
    fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) runCatching { KadansChannels.ensure(context, strings(), choice().style) }
    }

    override fun ringChanged() = ensureChannels()

    override fun soundFacts(): SoundFacts {
        val channel =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { notifications.getNotificationChannel(KadansChannels.reminders(choice().style)) }.getOrNull()
            } else {
                null
            }
        return SoundFacts(
            notificationsOn = notifications.areNotificationsEnabled(),
            // Set to silent, or to no sound at all, by the person in the system's settings.
            channelSilent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && channel != null &&
                (channel.importance < NotificationManager.IMPORTANCE_DEFAULT || channel.sound == null),
            ringer = when (audio.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> Ringer.Silent
                AudioManager.RINGER_MODE_VIBRATE -> Ringer.Vibrate
                else -> Ringer.Normal
            },
            alarmVolumeOff = audio.getStreamVolume(AudioManager.STREAM_ALARM) == 0,
        )
    }

    /** The system's page for the reminders' channel: its sound (any the phone has), its vibration, or none. */
    override fun openSoundSettings() {
        ensureChannels()
        val screen =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, KadansChannels.reminders(choice().style))
            } else {
                appDetails()
            }
        if (!open(screen)) open(appDetails())
    }

    /** A sample reminder, rung as a real one would be now, which leaves by itself. */
    override fun ringTest() {
        val s = strings()
        val sample = builder()
            .setContentTitle(s.testTitle)
            .setContentText(s.testBody)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) sample.setTimeoutAfter(TEST_SHOWN.inWholeMilliseconds)
        // Pressed twice, it rings twice; and it stops as a real one does (a touch in the app is an answer).
        Ringing.post(context, TEST_TAG, sample.build(), choice().keepRinging)
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
        /** How long the sample reminder of "Test" stays, and rings if reminders keep ringing. */
        val TEST_SHOWN = 20.seconds

        private const val TEST_TAG = "kadans.sound.test"
    }
}
