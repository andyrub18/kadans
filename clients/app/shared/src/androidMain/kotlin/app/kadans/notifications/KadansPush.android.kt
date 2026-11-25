package app.kadans.notifications

import app.kadans.sound.KadansChannels
import app.kadans.sound.KadansSound
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import app.kadans.api.model.UpcomingReminder
import app.kadans.pomodoro.CycleActions
import app.kadans.pomodoro.PhaseNotice
import app.kadans.pomodoro.SessionNotices
import app.kadans.sound.SoundPreferences
import app.kadans.push.DeviceRegistrar
import app.kadans.quiet.FocusQuiet
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
import app.kadans.widget.TodayWidget
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
        // Reminders and the ends of a session's phases have their own, each with its sound (ARCHITECTURE → "Sounds"),
        // named in the app's language.
        (GlobalContext.getOrNull()?.get<ReminderScheduler>() as? AndroidReminderScheduler)?.ensureChannels()
    }
}

/**
 * Foreground FCM messages don't reach the system tray by themselves — this shows them.
 * (Backgrounded, the system displays notification-type messages on its own.) Reminders go through [LocalReminders]:
 * one this phone already rang is not shown twice, and "reminders changed" fetches the window again. A focus session's
 * notices (a phase's end, a cycle done with its buttons) come as data, foreground or not, and are built here
 * ([SessionNotices]). A session changed on another device, for a phone that goes quiet during focus, shows nothing of
 * its own ([FocusQuiet]).
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
                // The home-screen widget shows the same todos: fetched again too, side by side with the window.
                if (koin != null) {
                    runCatching {
                        runBlocking {
                            withTimeoutOrNull(SYNC_WITHIN) {
                                launch { runCatching { koin.get<TodayWidget>().refresh() } }
                                koin.get<LocalReminders>().sync()
                            }
                        }
                    }
                }
                return
            }
            FocusQuiet.CHANGED_KIND -> {
                // A focus session changed on another device (ARCHITECTURE → "Do Not Disturb during focus"): the
                // sessions are read and the phone goes quiet, or gets its sound back, within the time Firebase gives a
                // message. Quiet, it shows its notice: what Firebase delivers at once is meant to show something.
                if (koin != null) {
                    runCatching { runBlocking { withTimeoutOrNull(SYNC_WITHIN) { koin.get<FocusQuiet>().refresh(show = true) } } }
                }
                return
            }
        }
        val title = message.notification?.title ?: message.data["title"] ?: return
        val body = message.notification?.body ?: message.data["body"] ?: ""
        // A focus session's notice (a phase's end, a cycle done with its buttons): built here, so that it rings as the
        // person asked and through Kadans' own quiet during focus (the server sends it as data to an app like this one).
        val phase = PhaseNotice.of(message.data["kind"], message.data, title, body)
        if (phase != null) {
            val keepRinging = koin?.getOrNull<SoundPreferences>()?.ring?.keepRinging == true
            SessionNotices.show(this, phase, koin?.getOrNull<CycleActions>(), keepRinging)
            return
        }
        runCatching {
            KadansPushChannel.ensure(this)
            // In the channel that has the sound it asks for (a phase's end), as the system would show it; else the default.
            val sound = KadansSound.of(message.data[KadansSound.DATA_KEY])
            val channel = KadansChannels.of(sound) ?: KadansPushChannel.ID
            val builder =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) android.app.Notification.Builder(this, channel)
                else @Suppress("DEPRECATION") android.app.Notification.Builder(this).apply {
                    if (sound != null) setSound(KadansChannels.uri(this@KadansMessagingService, sound), KadansChannels.attributes(alarm = false))
                }
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
