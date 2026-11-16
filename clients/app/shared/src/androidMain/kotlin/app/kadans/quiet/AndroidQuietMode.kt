package app.kadans.quiet

import android.app.AlarmManager
import android.app.AutomaticZenRule
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.notification.Condition
import android.service.notification.ZenPolicy
import androidx.annotation.RequiresApi
import app.kadans.api.model.ClockFormat
import app.kadans.auth.AndroidActivityHolder
import app.kadans.config.AndroidAppContext
import app.kadans.i18n.StringsCatalog
import app.kadans.shared.R
import app.kadans.ui.Dates
import app.kadans.ui.brand.KadansMark
import app.kadans.ui.focusLink
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import org.koin.core.context.GlobalContext

// Android 10 brought the way for an app to switch a mode of its own (setAutomaticZenRuleState). Before it, the only way
// is to flip the phone's main Do Not Disturb and put it back afterwards: not risked.
actual fun platformQuietMode(catalog: () -> StringsCatalog, clock: () -> ClockFormat): QuietMode =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) AndroidQuietMode(AndroidAppContext.context, catalog, clock) else NoQuietMode

/**
 * Do Not Disturb during focus on Android (ARCHITECTURE → "Do Not Disturb during focus"): a mode of Kadans' own in the
 * system's Do Not Disturb settings (an `AutomaticZenRule`), switched on while a focus phase counts down.
 *
 * - **Its own mode, never the phone's**: turning it off leaves the person's own Do Not Disturb, and any other mode,
 *   exactly as they were. Android combines the modes that are on; the strictest wins.
 * - **What gets through** is set once, when the mode is created, and is the person's to change in the system's settings
 *   from then on: alarms, music and videos, calls from starred contacts and repeat callers, reminders (Kadans' own are
 *   of that kind) and calendar events. Everything else is silent and does not pop up; it waits in the shade.
 * - **Android's access**: "Do Not Disturb access" to have a mode at all (it does not let an app read notifications),
 *   and "Alarms & reminders" for the exact alarm that brings the sound back ([wakeAt] → [QuietAlarmReceiver]).
 * - **A silent notice** while the phone is quiet: until when, a tap opening the session, and "Sound on"
 *   ([QuietActionReceiver]). It goes by itself a minute after the phase's end, whatever happens to the app.
 */
@RequiresApi(Build.VERSION_CODES.Q)
internal class AndroidQuietMode(
    private val context: Context,
    private val catalog: () -> StringsCatalog,
    private val clock: () -> ClockFormat,
) : QuietMode {
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val kept = context.getSharedPreferences(KEPT, Context.MODE_PRIVATE)

    override fun access(): QuietAccess = when {
        !notifications.isNotificationPolicyAccessGranted -> QuietAccess.DoNotDisturbOff
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarms.canScheduleExactAlarms() -> QuietAccess.ExactAlarmsOff
        else -> QuietAccess.Ready
    }

    override fun openSettings(missing: QuietAccess) {
        when {
            // Kadans' own switch where the system has that page; else the list of apps, as the documentation has it.
            missing == QuietAccess.DoNotDisturbOff ->
                open(Intent(ACCESS_DETAIL, packageUri())) ||
                    open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) ||
                    open(appDetails())
            missing == QuietAccess.ExactAlarmsOff && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri())) || open(appDetails())
            else -> Unit
        }
    }

    override fun quiet(span: QuietSpan?) {
        if (span == null) {
            ruleId()?.let { switch(it, on = false) }
            runCatching { notifications.cancel(NOTICE_TAG, NOTICE_ID) }
            return
        }
        val id = ruleId() ?: create() ?: return // the access went: nothing is quiet, and nothing says it is
        switch(id, on = true)
        kept.edit().putInt(BOOT, bootCount()).apply()
        show(span)
    }

    /**
     * The phone rings for everything although Kadans' mode should be on: the person turned Do Not Disturb off (the
     * quick settings' tile does that to every mode that is on). Asked of the phone's own state, not of the mode's:
     * Android 15's `getAutomaticZenRuleState` keeps answering "on" for a mode the person switched off (seen on the
     * emulator). With another mode of the person's on at the same time, nothing shows here: the phone is quiet anyway.
     * After a restart the system may have forgotten the mode was on: that is not the person's doing.
     */
    override fun turnedOffByPerson(): Boolean {
        if (kept.getInt(BOOT, bootCount()) != bootCount()) return false
        if (ruleId() == null) return false
        return runCatching { notifications.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL }.getOrDefault(false)
    }

    /** One exact alarm, on time in Doze. The permission gone between the check and here: [access] says so next. */
    override fun wakeAt(at: Instant?) {
        if (at == null) {
            alarms.cancel(wake())
            return
        }
        try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilliseconds(), wake())
        } catch (_: SecurityException) {
        }
    }

    override fun remove() {
        alarms.cancel(wake())
        runCatching { notifications.cancel(NOTICE_TAG, NOTICE_ID) }
        ruleId()?.let { id -> runCatching { notifications.removeAutomaticZenRule(id) } }
    }

    /** Kadans' mode among the app's own, by its condition; null when there is none, or the access is gone. */
    private fun ruleId(): String? =
        runCatching { notifications.automaticZenRules.entries.firstOrNull { it.value.conditionId == condition }?.key }.getOrNull()

    private fun create(): String? = runCatching { notifications.addAutomaticZenRule(rule()) }.getOrNull()

    /**
     * The mode as it is created: named in the app's language, opening Kadans' Settings from the system's (the app's
     * main screen takes that action, see the manifest). Never updated afterwards: what the person set on it stays.
     */
    private fun rule(): AutomaticZenRule {
        val q = catalog().quiet
        val settings = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            AutomaticZenRule.Builder(q.modeName, condition)
                .setConfigurationActivity(settings)
                .setZenPolicy(policy())
                .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                .setType(AutomaticZenRule.TYPE_OTHER)
                .setTriggerDescription(q.modeTrigger)
                .setIconResId(R.drawable.ic_stat_kadans)
                .build()
        } else {
            AutomaticZenRule(q.modeName, null, settings, condition, policy(), NotificationManager.INTERRUPTION_FILTER_PRIORITY, true)
        }
    }

    private fun policy(): ZenPolicy {
        val policy = ZenPolicy.Builder()
            .allowAlarms(true)
            .allowMedia(true) // music while focusing
            .allowCalls(ZenPolicy.PEOPLE_TYPE_STARRED)
            .allowRepeatCallers(true)
            .allowReminders(true) // Kadans' own are reminders: a "starts now" is not swallowed by a focus phase
            .allowEvents(true)
            .allowMessages(ZenPolicy.PEOPLE_TYPE_NONE)
            .allowSystem(false)
            // What is silenced still arrives: listed in the shade, without popping up or lighting the screen.
            .showAllVisualEffects()
            .showPeeking(false)
            .showFullScreenIntent(false)
            .showInAmbientDisplay(false)
            .showLights(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) policy.allowConversations(ZenPolicy.CONVERSATION_SENDERS_NONE)
        return policy.build()
    }

    private fun switch(id: String, on: Boolean) {
        val state = if (on) Condition.STATE_TRUE else Condition.STATE_FALSE
        runCatching { notifications.setAutomaticZenRuleState(id, Condition(condition, catalog().quiet.noticeTitle, state)) }
    }

    private fun show(span: QuietSpan) {
        val s = catalog()
        // Low: listed, never a sound. Named in the app's language (a later call renames it).
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_ID, s.quiet.section, NotificationManager.IMPORTANCE_LOW))
        val until = Dates(s.dates, clock()).time(span.until, TimeZone.currentSystemDefault())
        val left = (span.until - Clock.System.now() + NOTICE_LINGERS).inWholeMilliseconds.coerceAtLeast(1)
        val notice = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_kadans)
            .setColor(KadansMark.PURPLE_ARGB)
            .setContentTitle(s.quiet.noticeTitle)
            .setContentText(s.quiet.noticeUntil(until))
            .setCategory(Notification.CATEGORY_STATUS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setTimeoutAfter(left)
            .setContentIntent(openSession(span.todoId))
            .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_kadans), s.quiet.soundOn, soundOn()).build())
            .build()
        runCatching { notifications.notify(NOTICE_TAG, NOTICE_ID, notice) }
    }

    private fun wake(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, QuietAlarmReceiver::class.java).setAction(QuietAlarmReceiver.ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun soundOn(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, QuietActionReceiver::class.java).setAction(QuietActionReceiver.SOUND_ON),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The app on the session's focus link: brought forward if it is open, started if not. */
    private fun openSession(todoId: String): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(focusLink(todoId)))
            .setComponent(context.packageManager.getLaunchIntentForPackage(context.packageName)?.component)
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** `condition://<this app>/focus`: what tells Kadans' mode from any other the app may have one day. */
    private val condition: Uri = Condition.newId(context).appendPath("focus").build()

    private fun bootCount(): Int = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(0)

    private fun packageUri() = Uri.parse("package:${context.packageName}")

    private fun appDetails() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri())

    /** From the screen in front when there is one (the system's screen comes back to it), else as a task of its own. */
    private fun open(intent: Intent): Boolean = runCatching {
        val activity = AndroidActivityHolder.get()
        if (activity != null) activity.startActivity(intent) else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    private companion object {
        const val CHANNEL_ID = "kadans.quiet"
        const val NOTICE_TAG = "kadans.quiet"
        const val NOTICE_ID = 0
        const val KEPT = "kadans.quiet"
        const val BOOT = "boot"

        /** Do Not Disturb access for one app: its own page in the system's settings, where there is one. */
        const val ACCESS_DETAIL = "android.settings.NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS"

        /** How long after the phase's end the notice removes itself, should the app never be woken again. */
        val NOTICE_LINGERS = 1.minutes
    }
}

/**
 * The alarm: a focus phase began or ended by the timetable ([FocusQuiet.tick]). The sound coming back waits for nothing;
 * going quiet asks the server first, two seconds at most. Android hands an app its broadcasts one at a time, so nothing
 * here waits longer than that.
 */
class QuietAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        work { it.tick() }
    }

    internal companion object {
        const val ACTION = "app.kadans.quiet.TICK"
    }
}

/** The notice's "Sound on" (the app's own immutable PendingIntent): the sound is back for the rest of the session. */
class QuietActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SOUND_ON) return
        work { it.soundOn() }
    }

    internal companion object {
        const val SOUND_ON = "app.kadans.quiet.SOUND_ON"
    }
}

/**
 * What the system says of its own accord:
 * - a restart, an app update, "Alarms & reminders" just granted: the system keeps no alarm across them, so the mode and
 *   its alarm are set again from what is held, then the sessions are read ([FocusQuiet.restore]);
 * - Kadans' mode went off (Android 15 and later say so, whoever did it, Kadans included): the phone settles at once,
 *   which looks whether the person did, in the system's own settings ([FocusQuiet.modeWentOff]);
 * - the person removed or disabled Kadans' mode there: that is the switch, off ([FocusQuiet.modeRemoved]).
 */
class QuietSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> work { withTimeoutOrNull(RESTORE_WITHIN) { it.restore() } }
            NotificationManager.ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED -> {
                when (intent.getIntExtra(NotificationManager.EXTRA_AUTOMATIC_ZEN_RULE_STATUS, NotificationManager.AUTOMATIC_RULE_STATUS_UNKNOWN)) {
                    NotificationManager.AUTOMATIC_RULE_STATUS_DEACTIVATED -> work { it.modeWentOff() }
                    NotificationManager.AUTOMATIC_RULE_STATUS_REMOVED,
                    NotificationManager.AUTOMATIC_RULE_STATUS_DISABLED,
                    -> work { it.modeRemoved() }
                }
            }
        }
    }

    private companion object {
        /** Inside the ten seconds a receiver has; the app's next start finishes whatever the fetch did not. */
        val RESTORE_WITHIN = 8.seconds
    }
}

/**
 * The receiver's work, off the main thread, keeping the process alive until it is done ([BroadcastReceiver.goAsync]). A
 * failure is dropped, never a crash: the next news of a session, or the app's next start, sets things right.
 */
private fun BroadcastReceiver.work(block: suspend (FocusQuiet) -> Unit) {
    val quiet = GlobalContext.getOrNull()?.getOrNull<FocusQuiet>() ?: return
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        try {
            block(quiet)
        } catch (_: Exception) {
        } finally {
            pending.finish()
        }
    }
}
