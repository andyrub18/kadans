package app.kadans.sound

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import app.kadans.i18n.ReminderStrings
import app.kadans.shared.R

/**
 * The notification channels that carry Kadans' sounds (ARCHITECTURE → "Sounds"). On Android a sound belongs to a
 * channel, is fixed when the channel is made, and is the person's to change afterwards in the system's settings: so
 * each way of ringing is a channel of its own, and a reminder goes in the one the person chose. The server names the
 * same ids for what the system shows without the app (`FcmPushSender`): change one there, change it here.
 */
internal object KadansChannels {
    /** Until 1.2.0: reminders, with the phone's own notification sound. Gone once the app has made the others. */
    const val REMINDERS_BEFORE = "kadans.reminders"

    /** Reminders that ring like a notification: Kadans' sound, when the phone's sound is on. */
    const val REMINDERS_CHIME = "kadans.reminders.chime"

    /** Reminders that ring like an alarm: the same sound, on the alarm volume, whatever the ringer says. */
    const val REMINDERS_ALARM = "kadans.reminders.alarm"
    const val FOCUS_OVER = "kadans.focus.over"
    const val BREAK_OVER = "kadans.break.over"

    val VIBRATION: LongArray = longArrayOf(0, 250, 150, 250)

    fun reminders(style: ReminderStyle): String = if (style == ReminderStyle.Alarm) REMINDERS_ALARM else REMINDERS_CHIME

    /** The channel that has the sound a notice asks for; null for one that asks for none. */
    fun of(sound: KadansSound?): String? = when (sound) {
        KadansSound.FocusOver -> FOCUS_OVER
        KadansSound.BreakOver -> BREAK_OVER
        else -> null
    }

    /** The sound's file in the app (`res/raw`, written by `branding/sounds.py`), as Android names a resource. */
    fun uri(context: Context, sound: KadansSound): Uri {
        val raw = when (sound) {
            KadansSound.Reminder -> R.raw.kadans_reminder
            KadansSound.FocusOver -> R.raw.kadans_focus_over
            KadansSound.BreakOver -> R.raw.kadans_break_over
        }
        return Uri.parse("android.resource://${context.packageName}/$raw")
    }

    /** An alarm is heard on the alarm volume, which a phone on silent or on vibrate leaves alone. */
    fun attributes(alarm: Boolean): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (alarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /**
     * The channels as [style] wants them. Made again at each start: an existing channel keeps what the person set in
     * the system's settings, and only its name follows the app's language. The reminders' other channel, and the one
     * from before the choice, leave the system's list (Android gives a channel its settings back if it returns).
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun ensure(context: Context, strings: ReminderStrings, style: ReminderStyle) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val alarm = style == ReminderStyle.Alarm
        manager.createNotificationChannel(
            channel(context, reminders(style), if (alarm) strings.channelAlarm else strings.channelName, KadansSound.Reminder, alarm),
        )
        manager.createNotificationChannel(channel(context, FOCUS_OVER, strings.channelFocusOver, KadansSound.FocusOver, alarm = false))
        manager.createNotificationChannel(channel(context, BREAK_OVER, strings.channelBreakOver, KadansSound.BreakOver, alarm = false))
        listOf(REMINDERS_BEFORE, if (alarm) REMINDERS_CHIME else REMINDERS_ALARM).forEach { gone ->
            runCatching { manager.deleteNotificationChannel(gone) }
        }
    }

    /** Urgent (a heads-up, a sound): what rings is something the person asked to be told of, at that minute. */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun channel(context: Context, id: String, name: String, sound: KadansSound, alarm: Boolean) =
        NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(uri(context, sound), attributes(alarm))
            enableVibration(true)
            vibrationPattern = VIBRATION
        }
}
