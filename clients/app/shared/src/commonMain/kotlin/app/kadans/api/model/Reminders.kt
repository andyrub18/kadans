package app.kadans.api.model

import kotlin.time.Instant
import kotlinx.serialization.Serializable

/** Reminders this device rings itself (ARCHITECTURE → "Reminders ring on the phone"). */
@Serializable
data class ReminderSyncRequest(val installationId: String, val days: Int? = null)

/**
 * One reminder of the window, with the words the server wrote for it (the account's language and time zone). An
 * occurrence rings before its start and at the start ("starts now": [notifyAt] = [startsAt]); a zero lead is the start
 * alone. [pomodoroTemplateId]: a focus todo's, whose reminder offers "Start focus" instead of "Done".
 */
@Serializable
data class UpcomingReminder(
    val occurrenceId: String,
    val todoId: String,
    val title: String,
    val body: String,
    val notifyAt: Instant,
    val startsAt: Instant,
    val pomodoroTemplateId: String? = null,
    /** This device's own: rung again after "In 10 min", at [notifyAt]; the reminder it was, at this time. */
    val snoozedFrom: Instant? = null,
) {
    /** The reminder this is on the server (a snoozed one is the reminder it was). */
    val moment: Instant get() = snoozedFrom ?: notifyAt

    val isFocus: Boolean get() = !pomodoroTemplateId.isNullOrBlank()

    /**
     * Rung before its start ("in 5 min"): it informs, without buttons, and a tap opens the todo. Its buttons ("Start
     * focus" or "Done", "In 10 min") come with "starts now", the moment to decide: there, never 5 minutes early by a
     * slip of the finger, nor put off past the start. A snoozed reminder rings after the start, with them.
     */
    val isHeadsUp: Boolean get() = snoozedFrom == null && notifyAt < startsAt
}

@Serializable
data class ReminderWindowResponse(val syncedAt: Instant, val through: Instant, val reminders: List<UpcomingReminder>)

/**
 * Whether the reminder asked about (by its time) still rings: false once its occurrence is gone, done, cancelled or
 * moved, and while a focus session on the todo runs. [notifyAt] and [startsAt]: its reminder and start as they are now,
 * null once it has none.
 */
@Serializable
data class ReminderCheckResponse(val occurrenceId: String, val due: Boolean, val notifyAt: Instant? = null, val startsAt: Instant? = null)
