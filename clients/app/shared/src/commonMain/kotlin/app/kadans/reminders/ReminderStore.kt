package app.kadans.reminders

import app.kadans.api.KadansJson
import app.kadans.api.model.UpcomingReminder
import com.russhwolf.settings.Settings
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * What this device holds of its reminders, in the app's settings: the window as the server sent it (a few hundred
 * entries at most; what rings is always read from it, so a reminder dropped by the last sync never rings), which
 * reminders already rang (so an alarm and a push for the same one show it once), whether the server knows this device
 * rings reminders, and whether the person was already asked for the exact-alarm permission.
 */
class ReminderStore(private val settings: Settings) {
    fun window(): List<UpcomingReminder> =
        settings.getStringOrNull(WINDOW)?.let { runCatching { KadansJson.decodeFromString(windowSerializer, it) }.getOrNull() }.orEmpty()

    fun saveWindow(reminders: List<UpcomingReminder>) = settings.putString(WINDOW, KadansJson.encodeToString(windowSerializer, reminders))

    /** The server records this device as ringing its own reminders: it stops pushing those it has. */
    var syncing: Boolean
        get() = settings.getBoolean(SYNCING, false)
        set(value) = settings.putBoolean(SYNCING, value)

    /** The person has been asked once for the exact-alarm permission; never again unprompted. */
    var permissionAsked: Boolean
        get() = settings.getBoolean(ASKED, false)
        set(value) = settings.putBoolean(ASKED, value)

    /** This occurrence's reminder at this time (a moved one rings again) was shown, or settled, on this device. */
    fun rang(occurrenceId: String, notifyAt: Instant): Boolean = key(occurrenceId, notifyAt) in rung()

    /** Those of [reminders] not yet rung here, in their order. */
    fun unrung(reminders: List<UpcomingReminder>): List<UpcomingReminder> {
        val rung = rung()
        return reminders.filter { key(it.occurrenceId, it.notifyAt) !in rung }
    }

    fun markRung(reminders: Collection<UpcomingReminder>, now: Instant) {
        if (reminders.isEmpty()) return
        // Kept three days: long enough for a late push to find it, short enough to stay small.
        val kept = rung().filterValues { now - Instant.fromEpochMilliseconds(it) < RUNG_KEPT }
        val added = reminders.associate { key(it.occurrenceId, it.notifyAt) to now.toEpochMilliseconds() }
        settings.putString(RUNG, KadansJson.encodeToString(rungSerializer, kept + added))
    }

    /** Signed out: nothing of the account stays on the device. [permissionAsked] belongs to the device and stays. */
    fun clear() {
        settings.remove(WINDOW)
        settings.remove(RUNG)
        settings.remove(SYNCING)
    }

    private fun rung(): Map<String, Long> =
        settings.getStringOrNull(RUNG)?.let { runCatching { KadansJson.decodeFromString(rungSerializer, it) }.getOrNull() }.orEmpty()

    private fun key(occurrenceId: String, notifyAt: Instant) = "$occurrenceId@${notifyAt.toEpochMilliseconds()}"

    private companion object {
        const val WINDOW = "kadans.reminders.window"
        const val RUNG = "kadans.reminders.rung"
        const val SYNCING = "kadans.reminders.syncing"
        const val ASKED = "kadans.reminders.permissionAsked"
        val RUNG_KEPT = 3.days
        val windowSerializer = ListSerializer(UpcomingReminder.serializer())
        val rungSerializer = MapSerializer(String.serializer(), Long.serializer())
    }
}
