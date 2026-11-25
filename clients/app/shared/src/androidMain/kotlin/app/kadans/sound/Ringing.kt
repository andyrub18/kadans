package app.kadans.sound

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.service.notification.StatusBarNotification

/**
 * A notification that rings as the person asked (ARCHITECTURE → "Sounds"): once, or again and again until it is
 * answered, [SoundPreferences.RINGS_FOR] at most. A reminder rings so, and a focus session's phase ending. What keeps
 * ringing is Android's own "insistent" notification: opening the notifications, tapping it or swiping it away stops
 * it. So does Kadans coming in front, or being touched ([answered]), and an alarm ends it when nobody did
 * ([RingStopReceiver]): it then stays where it is, silent, with its buttons.
 */
object Ringing {
    /** Something posted here may still be ringing: what a touch in the app asks before it asks the system. */
    @Volatile
    private var mayRing = false

    /**
     * Shows [notification] under [tag], ringing. Whatever [tag] showed is taken down first: Android keeps an insistent
     * notification ringing through an update of it, and a notice that takes another's place rings afresh.
     */
    internal fun post(context: Context, tag: String, notification: Notification, keepRinging: Boolean) {
        // What its alarm will know it by: a later notice under the same tag (the next phase's, a minute on) is another.
        val ring = SystemClock.elapsedRealtimeNanos()
        if (keepRinging) {
            notification.flags = notification.flags or Notification.FLAG_INSISTENT
            notification.extras.putLong(RING, ring)
        }
        val manager = manager(context)
        runCatching {
            manager.cancel(tag, ID)
            manager.notify(tag, ID, notification)
        }
        if (keepRinging) {
            mayRing = true
            stopAfter(context, tag, ring)
        }
    }

    /** In the place of whatever [tag] showed, ringing or not, a notification built [quietly]. */
    internal fun postQuietly(context: Context, tag: String, notification: Notification) = post(context, tag, notification, keepRinging = false)

    /**
     * Shown without a sound, a vibration or a heads-up, whatever its channel: a member of a group whose summary alone
     * would alert, and there is no summary (what `NotificationCompat.setSilent` does). Before Android 8 a notification
     * carries its own sound, which is taken off it.
     */
    internal fun Notification.Builder.quietly(): Notification.Builder {
        setOnlyAlertOnce(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setGroup(QUIET_GROUP).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
        } else {
            @Suppress("DEPRECATION")
            setSound(null).setVibrate(null).setDefaults(0)
        }
        return this
    }

    /**
     * The notice posted under [tag] as [ring] has rung long enough: it stays, silent. Nothing if it is gone, rings no
     * more, or another took its place meanwhile (that one has its own minute).
     */
    internal fun stop(context: Context, tag: String, ring: Long) {
        runCatching {
            manager(context).activeNotifications
                .filter { it.tag == tag && it.id == ID && it.notification.extras.getLong(RING) == ring }
                .forEach { silence(context, it) }
        }
    }

    /**
     * The person is in Kadans: whatever still rings has been heard, and stops. [cameBack] true when the app just came
     * in front, which asks the system what is shown (the app may have been started again since it rang); false for a
     * touch, which only does so when this run of the app set something ringing.
     */
    fun answered(context: Context, cameBack: Boolean) {
        if (!cameBack && !mayRing) return
        mayRing = false
        runCatching { manager(context).activeNotifications.forEach { silence(context, it) } }
    }

    /** The same notification, as it is shown, without its ringing: its words, its buttons and what a tap opens are kept. */
    private fun silence(context: Context, shown: StatusBarNotification) {
        if (shown.notification.flags and Notification.FLAG_INSISTENT == 0) return
        val quiet = Notification.Builder.recoverBuilder(context, shown.notification).quietly().build()
        quiet.flags = quiet.flags and Notification.FLAG_INSISTENT.inv()
        val manager = manager(context)
        manager.cancel(shown.tag, shown.id)
        manager.notify(shown.tag, shown.id, quiet)
    }

    /** One alarm per ringing notice, for when it has rung long enough. */
    private fun stopAfter(context: Context, tag: String, ring: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val at = System.currentTimeMillis() + SoundPreferences.RINGS_FOR.inWholeMilliseconds
        // Its own data URI: extras are not part of a PendingIntent's identity, and two notices never share an alarm.
        val intent = Intent(context, RingStopReceiver::class.java)
            .setAction(RingStopReceiver.ACTION)
            .setData(Uri.parse("kadans://ringing/${Uri.encode(tag)}/$ring"))
            .putExtra(RingStopReceiver.TAG, tag)
            .putExtra(RingStopReceiver.RING, ring)
        val stop = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            // To the second where Android allows it; a little late is harmless (the ring goes on that much longer).
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, stop)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, stop)
            }
        } catch (_: SecurityException) {
            runCatching { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, stop) }
        }
    }

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    /** Every notice Kadans tags is shown under this id: its tag alone tells it from the others. */
    private const val ID = 0
    private const val QUIET_GROUP = "kadans.quiet"

    /** In a ringing notice's extras: which posting of it this is. */
    private const val RING = "app.kadans.ring"
}

/** A notice that keeps ringing has rung for [SoundPreferences.RINGS_FOR] without an answer ([Ringing.stop]). Not exported. */
class RingStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        Ringing.stop(context, intent.getStringExtra(TAG) ?: return, intent.getLongExtra(RING, 0))
    }

    internal companion object {
        const val ACTION = "app.kadans.sound.STOP_RINGING"
        const val TAG = "tag"
        const val RING = "ring"
    }
}
