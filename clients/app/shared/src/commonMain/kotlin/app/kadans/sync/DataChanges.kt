package app.kadans.sync

import app.kadans.realtime.RealtimeEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge

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

    /** Each signal: the areas that may have changed. */
    val changes: Flow<Set<DataArea>> = merge(here, realtimeEvents.mapNotNull(::areasOf))

    /** This device changed [areas] outside the screens. */
    fun changed(vararg areas: DataArea) {
        here.tryEmit(areas.toSet())
    }

    /** Back in front: anything may have changed meanwhile. */
    fun cameBack() {
        here.tryEmit(DataArea.entries.toSet())
    }

    /** The signals concerning [area]. While a reload runs, those arriving meanwhile make one more, not one each. */
    fun of(area: DataArea): Flow<Unit> = changes.filter { area in it }.map { }.conflate()

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
