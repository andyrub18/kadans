package app.kadans.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Instances
import android.provider.Settings
import app.kadans.auth.AndroidActivityHolder
import app.kadans.config.AndroidAppContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

actual fun platformPhoneCalendar(): PhoneCalendarSource = AndroidPhoneCalendar(AndroidAppContext.context)

/**
 * The phone's calendar on Android (ARCHITECTURE → "The phone's calendar"): the system's calendar provider, which every
 * calendar app and account on the phone writes to (Google, Samsung, Outlook, a work account). Read only, with the
 * person's permission (`READ_CALENDAR`, asked when they turn the switch on, never at start).
 *
 * What is read: the occurrences between two moments ([Instances]: a repeating event counted each time), of the
 * calendars the person shows in their calendar app, without the events they declined and the cancelled ones. A title is
 * another person's text (an invitation can come from anyone): it is shown as plain text, cut, and never logged.
 */
internal class AndroidPhoneCalendar(private val context: Context) : PhoneCalendarSource {
    private val kept = context.getSharedPreferences(KEPT, Context.MODE_PRIVATE)

    override fun access(): CalendarAccess =
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) CalendarAccess.Granted
        else CalendarAccess.Denied

    /**
     * Android asks twice at most; refused twice, a request shows nothing and the permission is only in the system's
     * settings. It then answers "no need to explain", exactly as before the first request: having asked once tells the
     * two apart. The answer is read when the app is back in front ([PhoneCalendar.cameBack]).
     */
    override fun requestAccess() {
        val activity = AndroidActivityHolder.get() ?: return
        val asksNoMore = kept.getBoolean(ASKED, false) && !activity.shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR)
        if (asksNoMore) {
            runCatching { activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
        } else {
            kept.edit().putBoolean(ASKED, true).apply()
            activity.requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), REQUEST)
        }
    }

    override suspend fun events(from: Instant, to: Instant): List<PhoneEvent> = withContext(Dispatchers.IO) {
        val range = Instances.CONTENT_URI.buildUpon()
            .also { ContentUris.appendId(it, from.toEpochMilliseconds()) }
            .also { ContentUris.appendId(it, to.toEpochMilliseconds()) }
            .build()
        val events = ArrayList<PhoneEvent>()
        context.contentResolver.query(range, COLUMNS, SHOWN, null, "${Instances.BEGIN} ASC")?.use { rows ->
            while (events.size < MOST && rows.moveToNext()) {
                val begin = Instant.fromEpochMilliseconds(rows.getLong(2))
                val end = Instant.fromEpochMilliseconds(rows.getLong(3)).coerceAtLeast(begin)
                val allDay = rows.getInt(4) != 0
                events += PhoneEvent(
                    id = rows.getLong(0),
                    title = rows.getString(1).orEmpty().replace('\n', ' ').trim().take(TITLE),
                    start = begin,
                    end = end,
                    allDay = allDay,
                    // The provider keeps a whole day as midnight to midnight in UTC, whatever the phone's time zone.
                    firstDay = if (allDay) begin.toLocalDateTime(TimeZone.UTC).date else null,
                    lastDay = if (allDay) (if (end > begin) end - 1.milliseconds else begin).toLocalDateTime(TimeZone.UTC).date else null,
                    calendar = rows.getString(5).orEmpty().trim().take(TITLE),
                    color = if (rows.isNull(6)) null else rows.getInt(6),
                )
            }
        }
        events
    }

    override fun open(event: PhoneEvent) {
        val view = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.start.toEpochMilliseconds())
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end.toEpochMilliseconds())
        // No calendar app to show it (a phone with the provider and no app): nothing happens, the event is still listed.
        runCatching { AndroidActivityHolder.get()?.startActivity(view) }
    }

    private companion object {
        const val KEPT = "kadans.phoneCalendar"
        const val ASKED = "asked"
        const val REQUEST = 4_221_010

        /** A week of a busy calendar is a few dozen events, a month a few hundred: far under this. */
        const val MOST = 1_000

        /** A title or a calendar's name is shown on one or two lines: no more than this is kept of either. */
        const val TITLE = 300

        val COLUMNS = arrayOf(
            Instances.EVENT_ID,
            Instances.TITLE,
            Instances.BEGIN,
            Instances.END,
            Instances.ALL_DAY,
            Instances.CALENDAR_DISPLAY_NAME,
            Instances.DISPLAY_COLOR,
        )

        /** Calendars shown in the phone's calendar app; not cancelled; not declined by the person. */
        val SHOWN =
            "${Instances.VISIBLE} = 1" +
                " AND (${Instances.STATUS} IS NULL OR ${Instances.STATUS} != ${Instances.STATUS_CANCELED})" +
                " AND (${Instances.SELF_ATTENDEE_STATUS} IS NULL OR ${Instances.SELF_ATTENDEE_STATUS} != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED})"
    }
}
