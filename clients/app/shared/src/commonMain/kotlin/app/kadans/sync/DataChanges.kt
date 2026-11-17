package app.kadans.sync

import app.kadans.realtime.RealtimeEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

/** What a screen shows, by the name the server's "data.changed" gives it. */
enum class DataArea(val wire: String) { Todos("todos"), Budget("budget") }

/**
 * Tells the screens that what they show may be out of date, so they load it again quietly (ARCHITECTURE → "Screens stay
 * in step"):
 * - the server said the account's data changed: on another device, by a job, or through this device's notification
 *   buttons ("data.changed", a second later), or a focus session changed;
 * - the hub came back after a drop: what changed meanwhile was not heard;
 * - this app came back to the front: on Android the hub sleeps in the background ([cameBack]);
 * - a notification's button here changed it ([changed]): at once, even with the hub away.
 *
 * The REST API stays the source of truth: this only says when to ask it again.
 */
class DataChanges(realtimeEvents: Flow<RealtimeEvent>) {
    private val here = MutableSharedFlow<Set<DataArea>>(extraBufferCapacity = 8)

    private val heard: Flow<Set<DataArea>> = realtimeEvents.mapNotNull(::areasOf)

    /**
     * [reload] at each signal concerning [area], heard from this call on (`listen`): a screen's view model calls it as
     * it is made, so nothing said right after is missed. Signals arriving while [reload] runs make one more run after
     * it, not one each.
     */
    fun reloadOn(scope: CoroutineScope, area: DataArea, reload: suspend () -> Unit) {
        val asked = Channel<Unit>(Channel.CONFLATED)
        for (signals in sources(area)) scope.listen(signals) { asked.trySend(Unit) }
        scope.launch { for (ask in asked) reload() }
    }

    /**
     * The signals concerning [area], one flow per origin (this device, the hub), for what collapses them itself (the
     * widget). One flow per origin because a merged one could not be heard from the first moment (`listen`).
     */
    internal fun sources(area: DataArea): List<Flow<Unit>> = listOf(here, heard).map { origin -> origin.filter { area in it }.map { } }

    /** This device changed [areas] outside the screens. */
    fun changed(vararg areas: DataArea) {
        here.tryEmit(areas.toSet())
    }

    /** Back in front: anything may have changed meanwhile. */
    fun cameBack() {
        here.tryEmit(DataArea.entries.toSet())
    }

    internal companion object {
        fun areasOf(event: RealtimeEvent): Set<DataArea>? = when (event) {
            is RealtimeEvent.DataChanged -> DataArea.entries.filter { it.wire in event.areas }.toSet().ifEmpty { null }
            // A session shows on its todo (running, its recent sessions) and in the stats.
            is RealtimeEvent.PomodoroRunChanged -> setOf(DataArea.Todos)
            RealtimeEvent.Reconnected -> DataArea.entries.toSet()
            else -> null
        }
    }
}
