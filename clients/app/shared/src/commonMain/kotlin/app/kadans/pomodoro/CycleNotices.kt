package app.kadans.pomodoro

import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.i18n.StringsCatalog
import app.kadans.sound.KadansSound
import app.kadans.sync.DataArea
import app.kadans.sync.DataChanges
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A looping focus session's cycle ended and the session waits (ARCHITECTURE → Pomodoro): the notice asks for another
 * one. Its buttons: "Another cycle" starts it, "Done" finishes the session and marks its occurrence done (as a reminder's
 * "Done" does). Neither: the session counts nothing more and ends by itself. Android shows it with the buttons
 * (`SessionNotices`), the Linux desktop too (`DesktopCycleNotices`); elsewhere it is the server's words alone.
 * [occurrenceId]: the occurrence the session is for, null when none was near its start ("Done" then only finishes).
 */
data class CycleNotice(
    val runId: String,
    val todoId: String,
    val title: String,
    val body: String,
    val occurrenceId: String? = null,
) {
    companion object {
        /** The phase notification's kind, and its mark that a cycle ended (the server's `CycleDoneKey`). */
        const val KIND = "pomodoro.phase.completed"
        const val CYCLE_DONE = "cycleDone"

        /** The notice in a notification, pushed or live; null for any other (a phase change inside a cycle too). */
        fun of(kind: String?, data: Map<String, String>?, title: String?, body: String?): CycleNotice? {
            if (kind != KIND || data == null || data[CYCLE_DONE].isNullOrBlank()) return null
            return CycleNotice(
                runId = data["runId"]?.takeIf { it.isNotBlank() } ?: return null,
                todoId = data["todoId"]?.takeIf { it.isNotBlank() } ?: return null,
                title = title ?: return null,
                body = body.orEmpty(),
                occurrenceId = data["occurrenceId"]?.takeIf { it.isNotBlank() },
            )
        }
    }
}

/**
 * A focus session's notice as a device shows it itself (ARCHITECTURE → "Sounds"): a phase's end, "time's up", the
 * session over. It asks for a sound of its own ([KadansSound.DATA_KEY]), which is what tells it from any other
 * notification, and it belongs to a session: the next one takes its place. [cycle]: a cycle is done and the session
 * waits for the person, so the notice has its two buttons. [key]: which of the session's notices this is, the same
 * whether the push brought it or the app's live connection, so that a phone which gets both shows it once.
 */
data class PhaseNotice(
    val runId: String,
    val todoId: String,
    val title: String,
    val body: String,
    val sound: KadansSound,
    val cycle: CycleNotice? = null,
    val key: String = runId,
) {
    companion object {
        /**
         * How old a notice the live connection brings may be and still be shown from it. An open app hears a phase's
         * end there a moment after it happened, well before the push; one that comes later is a catch-up after a
         * reconnect, and the push showed it at the time.
         */
        val LIVE_WITHIN = 30.seconds

        fun isLive(createdAt: Instant, now: Instant): Boolean = now - createdAt <= LIVE_WITHIN

        /** The notice in a notification, pushed or live; null for any other (a reminder has its own way of ringing). */
        fun of(kind: String?, data: Map<String, String>?, title: String?, body: String?): PhaseNotice? {
            val sound = KadansSound.of(data?.get(KadansSound.DATA_KEY))?.takeIf { it != KadansSound.Reminder } ?: return null
            return PhaseNotice(
                runId = data?.get("runId")?.takeIf { it.isNotBlank() } ?: return null,
                todoId = data["todoId"]?.takeIf { it.isNotBlank() } ?: return null,
                title = title ?: return null,
                body = body.orEmpty(),
                sound = sound,
                cycle = CycleNotice.of(kind, data, title, body),
                key = listOf(kind, data["runId"], data["currentPhaseIndex"], data["status"], data[CycleNotice.CYCLE_DONE]).joinToString("|"),
            )
        }
    }
}

/**
 * What was shown, by its key, for [keptFor]: the same thing brought twice (a session's notice by the push and by the
 * live connection) is shown by whichever comes first. Not safe from two threads at once: its user sees to that.
 */
class ShownOnce(private val keptFor: Duration) {
    private val shown = mutableMapOf<String, Instant>()

    /** True the first time [key] is seen, false while it is remembered. */
    fun first(key: String, now: Instant): Boolean {
        shown.entries.removeAll { now - it.value > keptFor }
        if (key in shown) return false
        shown[key] = now
        return true
    }
}

/** What a cycle notice needs on every platform: its words ([strings], the app's language) and its two buttons. */
class CycleActions(
    private val api: KadansApi,
    private val dataChanges: DataChanges,
    private val catalog: () -> StringsCatalog,
) {
    val strings: StringsCatalog get() = catalog()

    /**
     * "Another cycle": the session goes on from the first phase, now. True as well when it cannot any more (ended by
     * itself, finished on another device): the notice is of no use then. False when the server could not be reached:
     * the notice stays, with its buttons.
     */
    suspend fun anotherCycle(notice: CycleNotice): Boolean = settles { api.pomodoro.resume(notice.runId) }

    /**
     * "Done": the session finishes and its occurrence is marked done. Either already so (another device, the session
     * ended by itself) counts as done, so pressing it again after a failure is safe.
     */
    suspend fun done(notice: CycleNotice): Boolean {
        if (!settles { api.pomodoro.finish(notice.runId) }) return false
        val occurrence = notice.occurrenceId ?: return true
        return settles(ALREADY_DONE, ALREADY_CANCELLED) { api.todos.completeOccurrence(occurrence) }
            .also { if (it) dataChanges.changed(DataArea.Todos) }
    }

    /** True once [call] went through, or the server says it is so already ([alreadySo]); false when it was not reached. */
    private suspend fun settles(vararg alreadySo: String = arrayOf(RUN_NOT_FOUND, RUN_NOT_RUNNING), call: suspend () -> Unit): Boolean =
        try {
            call()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: KadansApiException) {
            e.errorCode in alreadySo
        } catch (_: Exception) {
            false
        }

    internal companion object {
        const val RUN_NOT_FOUND = "10028"
        const val RUN_NOT_RUNNING = "10029"
        const val ALREADY_DONE = "10005"
        const val ALREADY_CANCELLED = "10020"
    }
}
