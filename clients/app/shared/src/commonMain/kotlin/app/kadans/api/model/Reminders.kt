package app.kadans.api.model

import kotlin.time.Instant
import kotlinx.serialization.Serializable

/** Reminders this device rings itself (ARCHITECTURE → "Reminders ring on the phone"). */
@Serializable
data class ReminderSyncRequest(val installationId: String, val days: Int? = null)

/** One reminder of the window, with the words the server wrote for it (the account's language and time zone). */
@Serializable
data class UpcomingReminder(
    val occurrenceId: String,
    val todoId: String,
    val title: String,
    val body: String,
    val notifyAt: Instant,
    val startsAt: Instant,
)

@Serializable
data class ReminderWindowResponse(val syncedAt: Instant, val through: Instant, val reminders: List<UpcomingReminder>)

/** Whether a reminder still rings at [notifyAt]: false once its occurrence is gone, done or cancelled. */
@Serializable
data class ReminderCheckResponse(val occurrenceId: String, val due: Boolean, val notifyAt: Instant? = null)
