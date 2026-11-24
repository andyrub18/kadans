package app.kadans.ui.pomodoro

import app.kadans.api.model.PomodoroPhaseType
import app.kadans.api.model.PomodoroRunResponse
import kotlin.time.Duration

/**
 * The cycle a session is in, as the strip under its dial draws it: one segment a phase, as wide as the phase is long.
 * A session that repeats its cycle shows the cycle it is in, not every one it has been through.
 */
object PhaseStrip {
    data class Segment(val isBreak: Boolean, val weight: Float, val ahead: Boolean)

    fun of(run: PomodoroRunResponse): List<Segment> {
        val cycle = if (run.loop && run.cycleLength > 0) run.cycleLength else run.phases.size
        if (cycle <= 0) return emptyList()
        val first = if (run.loop && run.cycleLength > 0) run.currentPhaseIndex / cycle * cycle else 0
        val phases = run.phases.drop(first).take(cycle)
        val longest = phases.maxOfOrNull { it.durationMinutes } ?: return emptyList()
        return phases.mapIndexed { index, phase ->
            Segment(
                isBreak = phase.type == PomodoroPhaseType.Break,
                // A one-minute break beside an hour of focus still shows: nothing is thinner than a twelfth of the longest.
                weight = maxOf(phase.durationMinutes, 1).toFloat().coerceAtLeast(longest / 12f),
                ahead = first + index > run.currentPhaseIndex,
            )
        }
    }

    /** How much of a phase of [minutes] is gone when [remaining] is left: 0 at its start, 1 at its end. */
    fun elapsed(minutes: Int?, remaining: Duration): Float {
        val total = (minutes ?: 0) * 60
        if (total <= 0) return 0f
        return (1f - remaining.inWholeSeconds.toFloat() / total).coerceIn(0f, 1f)
    }
}
