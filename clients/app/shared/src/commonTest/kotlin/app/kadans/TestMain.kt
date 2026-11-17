package app.kadans

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * `Dispatchers.Main` for a test of a view model, and back: what `@BeforeTest` and `@AfterTest` call, never
 * `Dispatchers.setMain` and `resetMain` themselves (`TestMainUseTest` checks it).
 *
 * A view model's coroutines are its own (`viewModelScope`), not the test's. A test that has seen what it came for
 * returns while the view model may still be waiting for an answer of the mock server; that answer arrives a moment
 * later on the HTTP engine's thread and takes the coroutine back to Main. kotlinx-coroutines-test refuses to change
 * Main at the very moment something uses it ("Dispatchers.Main is used concurrently with setting it"), and keeps the
 * refusal for the next change as well: one late answer failed the test that had just passed and the one after it, now
 * and then (first seen on CI in `BudgetLimitTests`, right after a build with nothing wrong in it). The app is not at
 * fault when that happens, and neither is the test. So the change waits for that moment to pass and is made again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object TestMain {
    fun set(dispatcher: CoroutineDispatcher) = settled { Dispatchers.setMain(dispatcher) }

    fun reset() = settled { Dispatchers.resetMain() }

    /** A late answer is over in microseconds; something that still refuses after this long is another matter, and is shown. */
    private val LONGEST = 2.seconds
    private val PAUSE = 2.milliseconds

    private inline fun settled(change: () -> Unit) {
        val started = TimeSource.Monotonic.markNow()
        while (true) {
            try {
                change()
                return
            } catch (refused: IllegalStateException) {
                if (started.elapsedNow() > LONGEST) throw refused
                // Common Kotlin has no sleep: the wait is a short spin.
                val paused = TimeSource.Monotonic.markNow()
                while (paused.elapsedNow() < PAUSE) Unit
            }
        }
    }
}
