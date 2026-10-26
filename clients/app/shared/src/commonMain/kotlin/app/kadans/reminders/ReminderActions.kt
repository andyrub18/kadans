package app.kadans.reminders

import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.api.model.PomodoroRunResponse
import app.kadans.api.model.UpcomingReminder
import app.kadans.sync.DataArea
import app.kadans.sync.DataChanges
import app.kadans.ui.pomodoro.PomodoroPreference
import kotlin.coroutines.cancellation.CancellationException

/**
 * What a reminder's buttons do, from the notification itself (Android's, the Linux desktop's): "Start focus" starts the
 * todo's session for that occurrence, "Done" marks it done, "In 10 min" rings it again then. Started or done, its other
 * reminders stay quiet on this device, and the app's screens load it again ([DataChanges]): the todo shows done, not
 * pending. The person is in a notification, not the app: never a crash, a result to show.
 */
class ReminderActions(
    private val api: KadansApi,
    private val reminders: LocalReminders,
    private val pomodoro: PomodoroPreference,
    private val dataChanges: DataChanges,
) {
    sealed interface Result {
        /** The session runs; [run] is null when it was already running (started elsewhere a moment before). */
        data class FocusStarted(val run: PomodoroRunResponse?) : Result

        data object Done : Result

        /** Offline, signed out, or refused: the reminder stays, with its buttons. */
        data object Failed : Result
    }

    /** A session as the todo's screen starts one: looping, hands-free as last chosen on this device, ending by itself. */
    suspend fun startFocus(reminder: UpcomingReminder): Result {
        val run = try {
            api.pomodoro.start(reminder.todoId, autoAdvance = pomodoro.handsFree, loop = true, occurrenceId = reminder.occurrenceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: KadansApiException) {
            if (e.errorCode != ALREADY_RUNNING) return Result.Failed
            null
        } catch (_: Exception) {
            return Result.Failed
        }
        reminders.settled(reminder.occurrenceId)
        dataChanges.changed(DataArea.Todos)
        return Result.FocusStarted(run)
    }

    /** Marked done; one done or cancelled elsewhere meanwhile is settled all the same. */
    suspend fun done(reminder: UpcomingReminder): Result {
        try {
            api.todos.completeOccurrence(reminder.occurrenceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: KadansApiException) {
            if (e.errorCode != ALREADY_DONE && e.errorCode != ALREADY_CANCELLED) return Result.Failed
        } catch (_: Exception) {
            return Result.Failed
        }
        reminders.settled(reminder.occurrenceId)
        dataChanges.changed(DataArea.Todos)
        return Result.Done
    }

    suspend fun snooze(reminder: UpcomingReminder) = reminders.snooze(reminder)

    internal companion object {
        /** `ErrorTypes`: PomodoroAlreadyActiveForTodo, TaskAlreadyCompleted, TaskAlreadyCancelled. */
        const val ALREADY_RUNNING = "10030"
        const val ALREADY_DONE = "10005"
        const val ALREADY_CANCELLED = "10020"
    }
}
