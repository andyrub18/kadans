package app.kadans.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import app.kadans.push.DeviceRegistrar
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

object KadansPushChannel {
    const val ID = "kadans.default"

    /** Create the channel at app start so system-displayed FCM messages land in it too. */
    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(ID, "Kadans", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }
}

/**
 * Foreground FCM messages don't reach the system tray by themselves — this shows them.
 * (Backgrounded, the system displays notification-type messages on its own.)
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
        val title = message.notification?.title ?: message.data["title"] ?: return
        val body = message.notification?.body ?: message.data["body"] ?: ""
        runCatching {
            KadansPushChannel.ensure(this)
            val builder =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) android.app.Notification.Builder(this, KadansPushChannel.ID)
                else @Suppress("DEPRECATION") android.app.Notification.Builder(this)
            val notification = builder
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setAutoCancel(true)
                .build()
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(title.hashCode(), notification)
        }
    }
}
