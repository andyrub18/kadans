package app.kadans.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.RemoteViews
import app.kadans.api.model.ClockFormat
import app.kadans.config.AndroidAppContext
import app.kadans.i18n.StringsCatalog
import app.kadans.shared.R
import app.kadans.ui.HOME_LINK
import app.kadans.ui.NEW_TODO_LINK
import app.kadans.ui.todoLink
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.koin.core.context.GlobalContext

actual fun platformWidgetSurface(catalog: () -> StringsCatalog, clock: () -> ClockFormat): WidgetSurface =
    AndroidWidgetSurface(AndroidAppContext.context, catalog, clock)

/**
 * The widgets on this phone's home screens, drawn as RemoteViews: "Today" and the date, then one line per pending todo,
 * as many as each widget's height holds, today's with a circle that marks them done. No library: a header and a few lines need none, and the client's versions
 * move as a set. Colours come from resources (light, and `values-night`): a widget follows the system's theme like its
 * neighbours, and turns with it without being drawn again.
 */
internal class AndroidWidgetSurface(
    private val context: Context,
    private val catalog: () -> StringsCatalog,
    private val clock: () -> ClockFormat,
) : WidgetSurface {
    override fun present(): Boolean = runCatching { ids(context).isNotEmpty() }.getOrDefault(false)

    override suspend fun show(entries: List<WidgetEntry>?, gap: WidgetGap?, doneAt: Map<String, Instant>) = withContext(Dispatchers.Default) {
        val manager = AppWidgetManager.getInstance(context)
        val words = catalog()
        val clock = clock()
        for (id in ids(context)) {
            val content = TodayWidgetContent.of(
                entries, gap, Clock.System.now(), TimeZone.currentSystemDefault(), words, clock, capacity(manager.getAppWidgetOptions(id)), doneAt,
            )
            // One widget that cannot be drawn (removed meanwhile) never keeps the others from being drawn.
            runCatching { manager.updateAppWidget(id, views(content, words, hourColumn(clock))) }
        }
    }

    /**
     * The hours' column, in pixels: wide enough for the clock's longest hour ("23:59", "12:59 PM") and the gap after
     * it, growing with the system's font size, so the titles start on one line whatever their hours.
     */
    private fun hourColumn(clock: ClockFormat): Int {
        val dp = if (clock == ClockFormat.TwelveHour) HOUR_COLUMN_12_DP else HOUR_COLUMN_24_DP
        return px(dp * context.resources.configuration.fontScale.coerceAtLeast(1f))
    }

    private fun px(dp: Float): Int = (dp * context.resources.displayMetrics.density).toInt()

    override fun canAdd(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && runCatching { AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported }.getOrDefault(false)

    override fun add() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // The launcher shows its own "Add to home screen?"; once confirmed, the system has the provider draw it.
        runCatching { AppWidgetManager.getInstance(context).requestPinAppWidget(ComponentName(context, TodayWidgetProvider::class.java), null, null) }
    }

    /**
     * How many lines fit under the header: the widget's height as the launcher reports it (the tallest it gets upright,
     * the shortest on its side), a line growing with the system's font size.
     */
    private fun capacity(options: Bundle?): Int {
        val upright = context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        val height = options?.getInt(if (upright) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) ?: 0
        if (height <= 0) return DEFAULT_LINES
        val line = LINE_DP * context.resources.configuration.fontScale.coerceAtLeast(1f)
        return ((height - CHROME_DP) / line).toInt().coerceIn(1, MAX_LINES)
    }

    private fun views(content: TodayWidgetContent, words: StringsCatalog, hourColumn: Int): RemoteViews {
        val widget = RemoteViews(context.packageName, R.layout.widget_today)
        widget.setTextViewText(R.id.widget_title, content.title)
        widget.setTextViewText(R.id.widget_date, content.date)
        widget.setContentDescription(R.id.widget_add, words.newTodo)
        // Anywhere that is not a todo or "+" opens the app on Home, the day's list (signed out: where it signs in).
        widget.setOnClickPendingIntent(android.R.id.background, open(HOME_LINK))
        widget.setOnClickPendingIntent(R.id.widget_header, open(HOME_LINK))
        widget.setOnClickPendingIntent(R.id.widget_add, open(NEW_TODO_LINK))
        widget.removeAllViews(R.id.widget_lines)
        content.lines.forEach { widget.addView(R.id.widget_lines, line(it, words, hourColumn)) }
        return widget
    }

    private fun line(line: WidgetLine, words: StringsCatalog, hourColumn: Int): RemoteViews = when (line) {
        is WidgetLine.Todo -> todo(line, words, hourColumn)
        is WidgetLine.Note -> text(R.layout.widget_today_note, line.text, opens = true)
        is WidgetLine.Label -> text(R.layout.widget_today_label, line.text, opens = false)
        // Under the titles: past the circle's place and the hours' column.
        is WidgetLine.More -> text(R.layout.widget_today_more, line.text, opens = true).apply {
            setViewPadding(R.id.widget_line, px(CIRCLE_DP) + hourColumn, 0, px(SIDE_DP), 0)
        }
    }

    /**
     * A todo's line, in one of three layouts: with its circle (today's), ticked and struck through with "Undo" (its
     * circle was just tapped), or without a circle (tomorrow's). The hour and the title open the todo in all three.
     */
    private fun todo(line: WidgetLine.Todo, words: StringsCatalog, hourColumn: Int): RemoteViews {
        val occurrence = line.done
        val layout = when {
            occurrence == null -> R.layout.widget_today_todo_plain
            line.undo -> R.layout.widget_today_todo_done
            else -> R.layout.widget_today_todo
        }
        return RemoteViews(context.packageName, layout).apply {
            setTextViewText(R.id.widget_line_time, line.time)
            setInt(R.id.widget_line_time, "setMinWidth", hourColumn)
            setTextViewText(R.id.widget_line_title, line.title)
            setOnClickPendingIntent(R.id.widget_line, open(todoLink(line.todoId)))
            when {
                occurrence == null -> Unit
                line.undo -> {
                    // Struck through: a paint flag, which a layout cannot say. The tick takes the tap back, as "Undo" does.
                    setInt(R.id.widget_line_title, "setPaintFlags", Paint.STRIKE_THRU_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG)
                    setTextViewText(R.id.widget_line_undo, words.widget.undo)
                    setOnClickPendingIntent(R.id.widget_line_undo, tap(TodayWidgetActionReceiver.UNDO, occurrence))
                    setOnClickPendingIntent(R.id.widget_line_check, tap(TodayWidgetActionReceiver.UNDO, occurrence))
                    setContentDescription(R.id.widget_line_check, words.widget.undo)
                }
                else -> {
                    setOnClickPendingIntent(R.id.widget_line_check, tap(TodayWidgetActionReceiver.DONE, occurrence))
                    setContentDescription(R.id.widget_line_check, words.widget.markDone(line.title))
                }
            }
        }
    }

    /**
     * A tap on a line's circle or its "Undo": a broadcast to [TodayWidgetActionReceiver] (not exported). Immutable; the
     * occurrence is in the intent's data, so each line and each of its two actions has its own.
     */
    private fun tap(action: String, occurrenceId: String): PendingIntent {
        val intent = Intent(context, TodayWidgetActionReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("kadans://widget/${action.substringAfterLast('.')}/$occurrenceId"))
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun text(layout: Int, text: String, opens: Boolean) = RemoteViews(context.packageName, layout).apply {
        setTextViewText(R.id.widget_line, text)
        if (opens) setOnClickPendingIntent(R.id.widget_line, open(HOME_LINK))
    }

    /**
     * The app, on [link] (Home, a todo, the form for a new one): brought forward if it is open (onNewIntent), started if
     * not. Immutable, to this app's own activity; the link is the intent's data, so each has its own.
     */
    private fun open(link: String): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
            .setComponent(context.packageManager.getLaunchIntentForPackage(context.packageName)?.component)
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    internal companion object {
        /**
         * A line's height (the layouts' minHeight: tall enough for a circle a thumb can hit), and what is not lines:
         * the header and the padding.
         */
        const val LINE_DP = 40
        const val CHROME_DP = 52

        /**
         * A line's side padding, the room a todo's circle takes before its hour (the todo layouts' paddingStart and
         * their 44 dp button), and the hours' column for each clock, its gap included.
         */
        const val SIDE_DP = 14f
        const val CIRCLE_DP = 46f
        const val HOUR_COLUMN_24_DP = 60f
        const val HOUR_COLUMN_12_DP = 84f

        /** Before the launcher has said how tall the widget is: what two cells hold. */
        const val DEFAULT_LINES = 3
        const val MAX_LINES = 24

        fun ids(context: Context): IntArray =
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, TodayWidgetProvider::class.java))
    }
}

/**
 * The system's calls about the widget (ARCHITECTURE → "The home-screen widget"). Placed, the phone restarted or the app
 * updated: drawn at once from what the phone holds, then fetched. Resized: drawn again with the lines that fit. The
 * first one placed starts what keeps it current ([TodayWidgetUpkeep]); the last one removed stops it.
 *
 * Exported, as a widget provider must be (the launcher is another app): it acts on the system's widget actions only,
 * reads nothing from the intent but the widget ids, and what it starts is one fetch every few minutes at most.
 */
class TodayWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Only with a widget really placed: anyone can send this call, and nothing should run every hour for none.
        if (AndroidWidgetSurface.ids(context).isEmpty()) return
        TodayWidgetUpkeep.start(context)
        work {
            it.redraw()
            it.refreshUnlessFresh()
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?) =
        work { it.redraw() }

    override fun onEnabled(context: Context) = TodayWidgetUpkeep.start(context)

    override fun onDisabled(context: Context) = TodayWidgetUpkeep.stop(context)
}

/**
 * A new day, or the phone's clock or time zone changed: "today" is another day. Drawn at once from what the phone holds
 * (tomorrow's todos were fetched with today's), then fetched; the next midnight's alarm is set. Not exported: only the
 * system and this app's own alarm reach it.
 */
class TodayWidgetClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            NEW_DAY, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> Unit
            else -> return
        }
        if (AndroidWidgetSurface.ids(context).isEmpty()) return
        TodayWidgetUpkeep.armNewDay(context)
        work {
            it.redraw()
            it.refresh()
        }
    }

    internal companion object {
        const val NEW_DAY = "app.kadans.widget.NEW_DAY"
    }
}

/**
 * A line's circle or "Undo" was tapped, or the five seconds of one ran out ([TodayWidget.markDone], `undo`, `sendDue`).
 * Each returns at once, never waiting out the five seconds here: Android hands an app its broadcasts one at a time, and
 * an "Undo" kept waiting behind the wait would arrive too late. The wait is an alarm ([TodayWidgetUpkeep.await]). Not
 * exported: only this app's own PendingIntents reach it.
 */
class TodayWidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val occurrenceId = intent.data?.lastPathSegment
        when (intent.action) {
            DONE -> if (occurrenceId != null) work { TodayWidgetUpkeep.await(context, it.markDone(occurrenceId)) }
            UNDO -> if (occurrenceId != null) work { TodayWidgetUpkeep.await(context, it.undo(occurrenceId)) }
            // A send the network holds up gives way after a few seconds (the next broadcast may be an "Undo"): what
            // did not go waits for a network.
            SEND -> work {
                val queue = withTimeoutOrNull(SEND_WITHIN) { it.sendDue() }
                TodayWidgetUpkeep.await(context, queue ?: TodayWidget.DoneQueue(nextDue = null, waitsForNetwork = true))
            }
        }
    }

    internal companion object {
        const val DONE = "app.kadans.widget.DONE"
        const val UNDO = "app.kadans.widget.UNDO"
        const val SEND = "app.kadans.widget.SEND"

        /** Shorter than the five seconds a tap waits: an "Undo" is never held up past its own line's moment. */
        val SEND_WITHIN = 3.seconds
    }
}

/**
 * Every hour, with a network, while a widget is placed: fetched again. What changes in between reaches the widget
 * sooner (the app itself, the server's "changed" push); this is for a phone that heard neither.
 */
class TodayWidgetRefreshJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        // The last widget went without the system saying so (a restored phone, a launcher changed): nothing to keep.
        if (AndroidWidgetSurface.ids(this).isEmpty()) {
            TodayWidgetUpkeep.stop(this)
            return false
        }
        val widget = GlobalContext.getOrNull()?.get<TodayWidget>() ?: return false
        scope.launch {
            try {
                widget.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Never a crash from the background: the next hour tries again.
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    /** The network went: the next hour tries again. */
    override fun onStopJob(params: JobParameters): Boolean {
        scope.coroutineContext.cancelChildren()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    internal companion object {
        const val ID = 4_221_002
        val EVERY = 1.hours

        /** The same work once, as soon as there is a network: a "Done" tapped offline is sent then ([TodayWidgetUpkeep.await]). */
        const val ONCE_ID = 4_221_003
    }
}

/**
 * What keeps a placed widget current without the app: an alarm at the next midnight (not waking the phone: it rings
 * when the phone next wakes, which is when someone can look) and the hourly [TodayWidgetRefreshJob].
 */
internal object TodayWidgetUpkeep {
    fun start(context: Context) {
        armNewDay(context)
        val jobs = context.getSystemService(JobScheduler::class.java) ?: return
        if (jobs.getPendingJob(TodayWidgetRefreshJob.ID) != null) return // scheduling it again would restart its clock
        val job = JobInfo.Builder(TodayWidgetRefreshJob.ID, ComponentName(context, TodayWidgetRefreshJob::class.java))
            .setPeriodic(TodayWidgetRefreshJob.EVERY.inWholeMilliseconds)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true) // across reboots (RECEIVE_BOOT_COMPLETED)
            .build()
        runCatching { jobs.schedule(job) }
    }

    fun stop(context: Context) {
        runCatching { context.getSystemService(AlarmManager::class.java)?.cancel(newDay(context)) }
        runCatching { context.getSystemService(JobScheduler::class.java)?.cancel(TodayWidgetRefreshJob.ID) }
    }

    /**
     * After a tap on a circle or "Undo", or a send: an alarm for the moment the next tapped "Done" is due (exact where
     * the phone allows it; a plain one this close rings on time too), waking the phone since the person may have put
     * it down at once; and, when one is due and could not be sent, a job that runs as soon as there is a network.
     */
    fun await(context: Context, queue: TodayWidget.DoneQueue) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val due = queue.nextDue
        if (alarms != null && due == null) runCatching { alarms.cancel(send(context)) }
        if (alarms != null && due != null) {
            val at = SystemClock.elapsedRealtime() + (due - Clock.System.now()).inWholeMilliseconds.coerceAtLeast(0) + SEND_AFTER_MS
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || runCatching { alarms.canScheduleExactAlarms() }.getOrDefault(false)
            runCatching {
                if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, send(context))
                else alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, send(context))
            }.onFailure {
                // The permission went between the check and the call.
                runCatching { alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, send(context)) }
            }
        }
        if (queue.waitsForNetwork) {
            val job = JobInfo.Builder(TodayWidgetRefreshJob.ONCE_ID, ComponentName(context, TodayWidgetRefreshJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
            runCatching { context.getSystemService(JobScheduler::class.java)?.schedule(job) }
        }
    }

    /** A moment after the five seconds, so the line is past its moment when the alarm asks. */
    private const val SEND_AFTER_MS = 150L

    private fun send(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, TodayWidgetActionReceiver::class.java).setAction(TodayWidgetActionReceiver.SEND),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * The next midnight's alarm. Exact where the phone allows it ("Alarms & reminders", which the reminders ask for):
     * a plain `set` may be delivered late by three quarters of the wait, hours for an alarm set a day ahead, and the
     * widget would go on calling yesterday "Today". Without it, within ten minutes, the shortest window Android keeps.
     * Not a wake-up alarm either way: with the phone asleep nobody is looking, and it rings as the phone wakes.
     */
    fun armNewDay(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val zone = TimeZone.currentSystemDefault()
        val midnight = (Clock.System.now().toLocalDateTime(zone).date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone) + 1.seconds).toEpochMilliseconds()
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || runCatching { alarms.canScheduleExactAlarms() }.getOrDefault(false)
        runCatching {
            if (exact) alarms.setExact(AlarmManager.RTC, midnight, newDay(context))
            else alarms.setWindow(AlarmManager.RTC, midnight, NEW_DAY_WITHIN.inWholeMilliseconds, newDay(context))
        }.onFailure {
            // The permission went between the check and the call.
            runCatching { alarms.setWindow(AlarmManager.RTC, midnight, NEW_DAY_WITHIN.inWholeMilliseconds, newDay(context)) }
        }
    }

    private val NEW_DAY_WITHIN = 10.minutes

    private fun newDay(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, TodayWidgetClockReceiver::class.java).setAction(TodayWidgetClockReceiver.NEW_DAY),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/**
 * A receiver's work, off the main thread, keeping the process alive until it is done ([BroadcastReceiver.goAsync]),
 * inside the ten seconds a receiver has. A failure is dropped, never a crash: the next signal sets things right.
 */
private fun BroadcastReceiver.work(block: suspend (TodayWidget) -> Unit) {
    val widget = GlobalContext.getOrNull()?.get<TodayWidget>() ?: return
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        try {
            withTimeoutOrNull(WITHIN) { block(widget) }
        } catch (_: Exception) {
        } finally {
            pending.finish()
        }
    }
}

private val WITHIN = 8.seconds
