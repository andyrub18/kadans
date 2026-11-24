package app.kadans.ui

import app.kadans.api.model.PomodoroPhaseType
import app.kadans.api.model.PomodoroRunPhaseResponse
import app.kadans.api.model.PomodoroRunResponse
import app.kadans.api.model.PomodoroRunStatus
import app.kadans.ui.pomodoro.PhaseStrip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The strip under a session's dial: one segment a phase, as wide as it is long, the ones still to come paler. */
class PhaseStripTests {
    private val at = Instant.parse("2026-11-24T14:00:00Z")

    private fun run(minutes: List<Int>, current: Int, loop: Boolean = false, cycleLength: Int = 0) = PomodoroRunResponse(
        id = "r",
        todoId = "t",
        status = PomodoroRunStatus.Active,
        currentPhaseIndex = current,
        loop = loop,
        cycleLength = cycleLength,
        phases = minutes.mapIndexed { index, m ->
            PomodoroRunPhaseResponse("p$index", index, if (index % 2 == 0) PomodoroPhaseType.Focus else PomodoroPhaseType.Break, m)
        },
        startedAt = at,
        updatedAt = at,
    )

    @Test
    fun aSegmentIsAsWideAsItsPhaseIsLongAndThoseAheadAreMarked() {
        val strip = PhaseStrip.of(run(listOf(25, 5, 25, 30), current = 1))

        assertEquals(listOf(25f, 5f, 25f, 30f), strip.map { it.weight })
        assertEquals(listOf(false, true, false, true), strip.map { it.isBreak })
        assertEquals(listOf(false, false, true, true), strip.map { it.ahead })
    }

    @Test
    fun aVeryShortPhaseStillShows() {
        val strip = PhaseStrip.of(run(listOf(120, 1), current = 0))

        // Nothing is thinner than a twelfth of the longest.
        assertEquals(10f, strip[1].weight)
    }

    @Test
    fun aSessionThatRepeatsShowsTheCycleItIsIn() {
        // Two cycles of four phases gone through one after the other; the sixth phase is the second of the second cycle.
        val strip = PhaseStrip.of(run(listOf(25, 5, 25, 30, 25, 5, 25, 30), current = 5, loop = true, cycleLength = 4))

        assertEquals(4, strip.size)
        assertEquals(listOf(false, false, true, true), strip.map { it.ahead })
    }

    @Test
    fun aSessionWithoutPhasesHasNoStrip() {
        assertTrue(PhaseStrip.of(run(emptyList(), current = 0)).isEmpty())
    }

    @Test
    fun aPhaseFillsFromItsStartToItsEnd() {
        assertEquals(0f, PhaseStrip.elapsed(25, 25.minutes))
        assertEquals(0.5f, PhaseStrip.elapsed(10, 5.minutes))
        assertEquals(1f, PhaseStrip.elapsed(25, 0.seconds))
        // A phase made longer than its minutes, or one of unknown length, never draws outside the ring.
        assertEquals(0f, PhaseStrip.elapsed(25, 40.minutes))
        assertEquals(0f, PhaseStrip.elapsed(null, 5.minutes))
    }
}
