package app.kadans.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import app.kadans.api.model.UpcomingReminder
import app.kadans.pomodoro.CycleActions
import app.kadans.pomodoro.CycleNotice
import app.kadans.pomodoro.PomodoroCycleNotices
import app.kadans.push.DeviceRegistrar
import app.kadans.reminders.AndroidReminderScheduler
import app.kadans.reminders.LocalReminders
import app.kadans.reminders.ReminderScheduler
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import app.kadans.shared.R
import app.kadans.ui.brand.KadansMark

object KadansPushChannel {
    const val ID = "kadans.default"

    /** Create the channels at app start so system-displayed FCM messages land in them too. */
    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(ID, "Kadans", NotificationManager.IMPORTANCE_DEFAULT)
        )
        // Reminders have their own (ARCHITECTURE → "Reminders ring on the phone"), named in the app's language.
        (GlobalContext.getOrNull()?.get<ReminderScheduler>() as? AndroidReminderScheduler)?.ensureChannel()
    }
}

/**
 * Foreground FCM messages don't reach the system tray by themselves — this shows them.
 * (Backgrounded, the system displays notification-type messages on its own.) Reminders go through [LocalReminders]:
 * one this phone already rang is not shown twice, and "reminders changed" fetches the window again. A focus session's
 * new cycle comes as data, foreground or not, and is shown with its button ([PomodoroCycleNotices]).
 */
class KadansMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        // Re-register so the backend targets the fresh token, if the app is wired up already.
        val koin = GlobalContext.getOrNull() ?: return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { koin.get<DeviceRegistrar>().register() }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val koin = GlobalContext.getOrNull()
        when (message.data["kind"]) {
            LocalReminders.DUE_KIND -> reminderOf(message)?.let { reminder ->
                if (koin == null) return@let
                runCatching { runBlocking { koin.get<LocalReminders>().pushed(reminder) } }
                return
            }
            LocalReminders.CHANGED_KIND -> {
                // Even when the hub seems connected: Android freezes an app in the background, and the server may have
                // dropped a connection the app still believes open. Within the time Firebase gives a message.
                if (koin != null)
                    runCatching { runBlocking { withTimeoutOrNull(SYNC_WITHIN) { koin.get<LocalReminders>().sync() } } }
                return
            }
        }
        val title = message.notification?.title ?: message.data["title"] ?: return
        val body = message.notification?.body ?: message.data["body"] ?: ""
        // A focus session's new cycle: with "Finish session" (the server sends it as data to an app like this one).
        val cycle = CycleNotice.of(message.data["kind"], message.data, title, body)
        val cycleActions = koin?.getOrNull<CycleActions>()
        if (cycle != null && cycleActions != null) {
            PomodoroCycleNotices.show(this, cycle, cycleActions)
            return
        }
        runCatching {
            KadansPushChannel.ensure(this)
            val builder =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) android.app.Notification.Builder(this, KadansPushChannel.ID)
                else @Suppress("DEPRECATION") android.app.Notification.Builder(this)
            builder
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(android.app.Notification.BigTextStyle().bigText(body))
                .setSmallIcon(R.drawable.ic_stat_kadans)
                .setColor(KadansMark.PURPLE_ARGB)
                .setAutoCancel(true)
            // A push with a link (a limit's alert opens the budget) opens it, as the system does for one it shows.
            message.data["link"]?.takeIf { it.startsWith("kadans://") }?.let { builder.setContentIntent(openLink(it)) }
            val notification = builder.build()
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(title.hashCode(), notification)
        }
    }

    private fun openLink(link: String): android.app.PendingIntent {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link))
            .setComponent(packageManager.getLaunchIntentForPackage(packageName)?.component)
            .setPackage(packageName)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return android.app.PendingIntent.getActivity(
            this, link.hashCode(), intent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        val SYNC_WITHIN = 8.seconds

        /** The reminder a push carries (`ReminderNotification.Message`): its words in the notification or, for a phone that rings reminders, in the data. */
        fun reminderOf(message: RemoteMessage): UpcomingReminder? {
            val data = message.data
            val startsAt = data["scheduledAt"]?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
            return UpcomingReminder(
                occurrenceId = data["occurrenceId"] ?: return null,
                todoId = data["todoId"] ?: return null,
                title = message.notification?.title ?: data["title"] ?: return null,
                body = message.notification?.body ?: data["body"] ?: "",
                notifyAt = data["notifyAt"]?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: startsAt,
                startsAt = startsAt,
                pomodoroTemplateId = data["pomodoroTemplateId"]?.ifBlank { null },
            )
        }
    }
}
