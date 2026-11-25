package app.kadans.pomodoro

import app.kadans.sound.KadansChannels
import app.kadans.sound.KadansSound
import app.kadans.sound.Ringing
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import app.kadans.notifications.KadansPushChannel
import app.kadans.shared.R
import app.kadans.ui.brand.KadansMark
import app.kadans.ui.focusLink
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext

/**
 * A focus session's notices on Android, built from their push (ARCHITECTURE → "Sounds"): a phase's end, "time's up",
 * the session over, in the channel that has its sound ([KadansChannels]). The app builds them, not the system, for what
 * only the app can give them: they count as a timer's alarm, so Kadans' own quiet during focus lets "back to focus"
 * through; they keep ringing until answered if the person asked ([Ringing]); and one per session, the next phase's
 * taking its place. A tap opens the session. A cycle done while the session waits ([PhaseNotice.cycle]) has "Another
 * cycle" and "Done" ([PomodoroActionReceiver]); a button whose request failed shows it again, quietly, saying Kadans
 * could not be reached.
 */
internal object SessionNotices {
    fun show(context: Context, notice: PhaseNotice, actions: CycleActions?, keepRinging: Boolean, failed: Boolean = false) {
        KadansPushChannel.ensure(context)
        val body = if (failed && actions != null) "${notice.body}\n${actions.strings.reminders.actionFailed}" else notice.body
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, KadansChannels.of(notice.sound) ?: KadansChannels.FOCUS_OVER)
            else @Suppress("DEPRECATION") Notification.Builder(context)
                .setPriority(Notification.PRIORITY_HIGH)
                .setSound(KadansChannels.uri(context, notice.sound), KadansChannels.attributes(alarm = false))
                .setVibrate(KadansChannels.VIBRATION)
        builder
            .setSmallIcon(R.drawable.ic_stat_kadans)
            .setColor(KadansMark.PURPLE_ARGB)
            .setContentTitle(notice.title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            // A timer the person started: what Do Not Disturb lets through as an alarm, Kadans' own mode included.
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openSession(context, notice.todoId))
        val cycle = notice.cycle
        if (cycle != null && actions != null) {
            builder
                .addAction(button(context, cycle, PomodoroActionReceiver.ANOTHER, actions.strings.pomodoro.anotherCycle))
                .addAction(button(context, cycle, PomodoroActionReceiver.DONE, actions.strings.reminders.done))
        }
        // Tagged with the session: the next phase's notice replaces this one.
        with(Ringing) {
            if (failed) postQuietly(context, notice.runId, builder.quietly().build())
            else post(context, notice.runId, builder.build(), keepRinging)
        }
    }

    /** Answered (a button pressed): the notice goes, and with it its ringing. */
    fun dismiss(context: Context, runId: String) {
        runCatching { manager(context).cancel(runId, 0) }
    }

    private fun manager(context: Context) = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** The app on the session's focus link: brought forward if it is open, started if not. */
    private fun openSession(context: Context, todoId: String): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(focusLink(todoId)))
            .setComponent(context.packageManager.getLaunchIntentForPackage(context.packageName)?.component)
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // Its own per todo: extras and data are what tell two sessions' taps apart.
        return PendingIntent.getActivity(context, todoId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * A broadcast to [PomodoroActionReceiver] (not exported), its own data URI per session and button: extras are not
     * part of a PendingIntent's identity.
     */
    private fun button(context: Context, notice: CycleNotice, action: String, label: String): Notification.Action {
        val intent = Intent(context, PomodoroActionReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("kadans://pomodoro/${notice.runId}/$action"))
            .putExtra(PomodoroActionReceiver.RUN, notice.runId)
            .putExtra(PomodoroActionReceiver.TODO, notice.todoId)
            .putExtra(PomodoroActionReceiver.TITLE, notice.title)
            .putExtra(PomodoroActionReceiver.BODY, notice.body)
            .putExtra(PomodoroActionReceiver.OCCURRENCE, notice.occurrenceId)
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_kadans), label, pending).build()
    }
}

/**
 * A cycle notice's button: the notice goes at once, and its ringing with it; "Another cycle" or "Done" then asks the
 * server within the time a receiver has, and only a request that failed brings the notice back, saying why.
 */
class PomodoroActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action?.takeIf { it == ANOTHER || it == DONE } ?: return
        val notice = CycleNotice(
            runId = intent.getStringExtra(RUN) ?: return,
            todoId = intent.getStringExtra(TODO) ?: return,
            title = intent.getStringExtra(TITLE).orEmpty(),
            body = intent.getStringExtra(BODY).orEmpty(),
            occurrenceId = intent.getStringExtra(OCCURRENCE),
        )
        val actions = GlobalContext.getOrNull()?.getOrNull<CycleActions>() ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // Pressed is answered: gone before the server's word, which can take seconds on a phone's network.
                SessionNotices.dismiss(context, notice.runId)
                val settled = withTimeoutOrNull(WITHIN) {
                    if (action == ANOTHER) actions.anotherCycle(notice) else actions.done(notice)
                }
                if (settled != true) {
                    val again = PhaseNotice(notice.runId, notice.todoId, notice.title, notice.body, KadansSound.FocusOver, cycle = notice)
                    SessionNotices.show(context, again, actions, keepRinging = false, failed = true)
                }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }

    internal companion object {
        const val ANOTHER = "app.kadans.pomodoro.ANOTHER_CYCLE"
        const val DONE = "app.kadans.pomodoro.DONE"
        const val RUN = "runId"
        const val TODO = "todoId"
        const val TITLE = "title"
        const val BODY = "body"
        const val OCCURRENCE = "occurrenceId"

        /** Inside the ten seconds a receiver has: the server's answer, or "could not be reached". */
        val WITHIN = 8.seconds
    }
}
