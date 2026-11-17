package app.kadans.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * `listen`: what the app's background services hear the hub and each other with. It subscribes before it returns, so
 * a signal sent right after is heard; a `launch { collect }` on a background dispatcher subscribes a moment later
 * and loses it (seen on CI, 2026-11-17: a reminders test told its object something at once, and nothing heard it).
 */
class ListeningTests {
    private fun background() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun what_is_sent_right_after_is_heard() = runTest {
        withContext(Dispatchers.Default) {
            repeat(500) { round ->
                val scope = background()
                val signals = MutableSharedFlow<Int>(extraBufferCapacity = 4)
                val heard = Channel<Int>(Channel.UNLIMITED)

                scope.listen(signals) { heard.send(it) }
                signals.emit(round)

                assertEquals(round, withTimeout(5.seconds) { heard.receive() })
                scope.cancel()
            }
        }
    }

    @Test
    fun it_hears_through_a_filter_and_a_map() = runTest {
        withContext(Dispatchers.Default) {
            val scope = background()
            val signals = MutableSharedFlow<Int>(extraBufferCapacity = 4)
            val heard = Channel<String>(Channel.UNLIMITED)

            scope.listen(signals.filter { it % 2 == 0 }.map { "even $it" }) { heard.send(it) }
            signals.emit(1)
            signals.emit(2)

            assertEquals("even 2", withTimeout(5.seconds) { heard.receive() })
            scope.cancel()
        }
    }

    @Test
    fun every_signal_is_heard_in_its_order() = runTest {
        withContext(Dispatchers.Default) {
            val scope = background()
            val signals = MutableSharedFlow<Int>(extraBufferCapacity = 64)
            val heard = Channel<Int>(Channel.UNLIMITED)

            scope.listen(signals) { heard.send(it) }
            repeat(50) { signals.emit(it) }

            assertEquals(List(50) { it }, List(50) { withTimeout(5.seconds) { heard.receive() } })
            scope.cancel()
        }
    }

    @Test
    fun a_scope_that_is_over_listens_to_nothing() = runTest {
        val signals = MutableSharedFlow<Int>(extraBufferCapacity = 4)
        var heard = 0

        val job = CoroutineScope(Job().apply { cancel() }).listen(signals) { heard++ }
        signals.emit(1)

        assertTrue(job.isCancelled)
        assertEquals(0, signals.subscriptionCount.value)
        withContext(Dispatchers.Default) { delay(50) }
        assertEquals(0, heard)
    }

    @Test
    fun it_stops_with_its_job() = runTest {
        withContext(Dispatchers.Default) {
            val scope = background()
            val signals = MutableSharedFlow<Int>(extraBufferCapacity = 4)

            val job = scope.listen(signals) { }
            assertEquals(1, signals.subscriptionCount.value)
            job.cancel()
            job.join()

            assertEquals(0, signals.subscriptionCount.value)
            scope.cancel()
        }
    }
}
