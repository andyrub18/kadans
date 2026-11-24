package app.kadans.ui.home

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Todos marked done from a list, each sent after a few seconds in which the person can take it back (a circle is
 * easy to press by mistake, and the server has no "not done after all"): the home-screen widget's five seconds.
 * [ticked] is what shows as done meanwhile. [send] marks one done on the server; [sent] says how it went (null: fine).
 */
class Ticks(
    private val scope: CoroutineScope,
    private val wait: Duration = UNDO_WINDOW,
    private val send: suspend (occurrenceId: String) -> Unit,
    private val sent: suspend (occurrenceId: String, failure: Exception?) -> Unit = { _, _ -> },
) {
    private val waiting = mutableMapOf<String, Job>()
    private val _ticked = MutableStateFlow<Set<String>>(emptySet())
    val ticked: StateFlow<Set<String>> = _ticked.asStateFlow()

    fun tick(occurrenceId: String) {
        if (occurrenceId in waiting) return
        _ticked.update { it + occurrenceId }
        waiting[occurrenceId] = scope.launch {
            delay(wait)
            // Past this point there is no taking it back: the job is no longer the one [undo] cancels.
            waiting.remove(occurrenceId)
            val failure = try {
                send(occurrenceId)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
            // The list is read again before the circle lets go, so it never flickers back to empty.
            sent(occurrenceId, failure)
            _ticked.update { it - occurrenceId }
        }
    }

    /** Takes a tick back while it waits. False when it was already sent. */
    fun undo(occurrenceId: String): Boolean {
        val job = waiting.remove(occurrenceId) ?: return false
        job.cancel()
        _ticked.update { it - occurrenceId }
        return true
    }

    /** What still waits when the screen goes away for good: to send now, elsewhere, so that nothing ticked is lost. */
    fun abandon(): List<String> {
        val left = waiting.keys.toList()
        waiting.values.forEach { it.cancel() }
        waiting.clear()
        return left
    }

    companion object {
        val UNDO_WINDOW: Duration = 5.seconds
    }
}
