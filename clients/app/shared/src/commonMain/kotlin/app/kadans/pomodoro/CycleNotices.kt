package app.kadans.pomodoro

import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.i18n.StringsCatalog
import kotlin.coroutines.cancellation.CancellationException

/**
 * A looping focus session began its next cycle by itself (ARCHITECTURE → Pomodoro): the notice says so and asks whether
 * the person is done with the task. By default the session goes on; its button, "Finish session", ends it. Android
 * shows it with the button (`PomodoroCycleNotices`), the Linux desktop too (`DesktopCycleNotices`), elsewhere it is
 * the server's words alone.
 */
data class CycleNotice(val runId: String, val todoId: String, val title: String, val body: String) {
    companion object {
        /** The phase notification's kind, and its mark that a new cycle began (the server's `CycleDoneKey`). */
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
            )
        }
    }
}

/** What a cycle notice needs on every platform: its words ([strings], the app's language) and its button. */
class CycleActions(private val api: KadansApi, private val catalog: () -> StringsCatalog) {
    val strings: StringsCatalog get() = catalog()

    /**
     * "Finish session", as the session's own Finish: true once it is over, by this or already (finished on another
     * device, ended by itself). False when the server could not be reached: the notice stays, with its button.
     */
    suspend fun finish(notice: CycleNotice): Boolean =
        try {
            api.pomodoro.finish(notice.runId)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: KadansApiException) {
            e.errorCode == RUN_NOT_RUNNING || e.errorCode == RUN_NOT_FOUND
        } catch (_: Exception) {
            false
        }

    internal companion object {
        const val RUN_NOT_FOUND = "10028"
        const val RUN_NOT_RUNNING = "10029"
    }
}
