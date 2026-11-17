package app.kadans.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Listens to [signals] from this very call on: the subscription is made before `listen` returns, so what is sent
 * right after is heard. [action] then runs on the scope's own dispatcher, as with `launch`.
 *
 * A plain `launch { signals.collect { } }` subscribes when its coroutine first runs: on a background dispatcher that
 * is a moment later, and a shared flow keeps nothing for a listener that is not there yet. Whatever was sent in
 * between is lost without a trace: an object made and told something at once (a test does exactly that, and so does
 * an app that opens on a notification's button) never hears it.
 *
 * [signals] must be a shared flow, or operators over one that run in the collector (`filter`, `map`). `merge`,
 * `buffer` and `conflate` subscribe from a coroutine of their own, later again: listen to each source instead.
 */
internal fun <T> CoroutineScope.listen(signals: Flow<T>, action: suspend (T) -> Unit): Job =
    launch(start = CoroutineStart.UNDISPATCHED) { signals.collect { action(it) } }
