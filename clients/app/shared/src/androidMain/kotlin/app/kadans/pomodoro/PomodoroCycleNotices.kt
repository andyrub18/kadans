package app.kadans.pomodoro

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
 * A looping session's cycle done on Android (ARCHITECTURE → Pomodoro), built from its push: the server's words, a tap
 * opening the session, "Another cycle" and "Done" ([PomodoroActionReceiver]). Tagged with the session: the next cycle's
 * replaces it. A button whose request failed shows it again, quietly, saying Kadans could not be reached.
 */
internal object PomodoroCycleNotices {
    fun show(context: Context, notice: CycleNotice, actions: CycleActions, failed: Boolean = false) {
        KadansPushChannel.ensure(context)
        val s = actions.strings
        val body = if (failed) "${notice.body}\n${s.reminders.actionFailed}" else notice.body
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, KadansPushChannel.ID)
            else @Suppress("DEPRECATION") Notification.Builder(context)
        val notification = builder
            .setSmallIcon(R.drawable.ic_stat_kadans)
            .setColor(KadansMark.PURPLE_ARGB)
            .setContentTitle(notice.title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setOnlyAlertOnce(failed)
            .setAutoCancel(true)
            .setContentIntent(openSession(context, notice))
            .addAction(button(context, notice, PomodoroActionReceiver.ANOTHER, s.pomodoro.anotherCycle))
            .addAction(button(context, notice, PomodoroActionReceiver.DONE, s.reminders.done))
            .build()
        runCatching { manager(context).notify(notice.runId, 0, notification) }
    }

    fun dismiss(context: Context, notice: CycleNotice) {
        runCatching { manager(context).cancel(notice.runId, 0) }
    }

    private fun manager(context: Context) = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** The app on the session's focus link: brought forward if it is open, started if not. */
    private fun openSession(context: Context, notice: CycleNotice): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(focusLink(notice.todoId)))
            .setComponent(context.packageManager.getLaunchIntentForPackage(context.packageName)?.component)
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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
 * A cycle notice's button: "Another cycle" or "Done" asks the server within the time a receiver has; the notice goes, or
 * says why not.
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
                val settled = withTimeoutOrNull(WITHIN) {
                    if (action == ANOTHER) actions.anotherCycle(notice) else actions.done(notice)
                }
                if (settled == true) PomodoroCycleNotices.dismiss(context, notice)
                else PomodoroCycleNotices.show(context, notice, actions, failed = true)
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
