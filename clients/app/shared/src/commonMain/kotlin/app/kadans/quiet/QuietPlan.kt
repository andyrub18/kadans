package app.kadans.quiet

import app.kadans.api.model.PomodoroPhaseType
import app.kadans.api.model.PomodoroRunResponse
import app.kadans.api.model.PomodoroRunStatus
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * A focus phase of one session, from its start to its end: while it counts down, a phone with "Do Not Disturb during
 * focus" on is quiet (ARCHITECTURE → "Do Not Disturb during focus"). [until] is the phase's own end, the time its
 * notice names; the sound comes back a moment sooner ([QuietPlan.SOUND_BACK]).
 */
@Serializable
data class QuietSpan(val runId: String, val todoId: String, val from: Instant, val until: Instant) {
    /** When the phone gets its sound back for this phase. */
    val soundBackAt: Instant get() = until - QuietPlan.SOUND_BACK
}

/**
 * When a session's focus phases count down, read from the session as the server describes it. Pure: the phone holds
 * this timetable and follows it with its own alarm, app closed or offline.
 */
object QuietPlan {
    /**
     * The sound comes back this long before a focus phase ends: what Kadans says at that end ("time's up", the break
     * that begins) is heard, not swallowed by the quiet it ends.
     */
    val SOUND_BACK = 2.seconds

    /** A stretch shorter than this (a phase nearly over when the phone hears of it) is not worth switching for. */
    val WORTH = 10.seconds

    /**
     * A phase that begins within this has begun. The phone's clock and the server's differ by a moment, so a phase the
     * server just started can look a fraction of a second ahead, and Android rings no alarm nearer than five seconds:
     * waiting for that start would make the phone quiet five seconds late (seen on the emulator).
     */
    val BEGINS_WITHIN = 5.seconds

    /**
     * The focus phases of [run] that count down from [now] on, as far as they can be known:
     * - the current phase, while the session is active (paused, or over: none);
     * - hands-free, the phases after it too, each beginning as the one before ends, up to the end of the cycle, where
     *   the session waits for the person; a manual session waits at 0:00 after every phase, so only the current one;
     * - nothing past the session's own end ([PomodoroRunResponse.finishAt]).
     */
    fun of(run: PomodoroRunResponse, now: Instant): List<QuietSpan> {
        if (run.status != PomodoroRunStatus.Active) return emptyList()
        var end = run.phaseEndsAt ?: return emptyList()
        val phases = run.phases.sortedBy { it.order }
        val current = phases.getOrNull(run.currentPhaseIndex) ?: return emptyList()
        val spans = mutableListOf<QuietSpan>()

        fun add(type: PomodoroPhaseType, from: Instant, until: Instant) {
            if (type != PomodoroPhaseType.Focus) return
            val stop = run.finishAt?.let { minOf(it, until) } ?: until
            if (stop - SOUND_BACK - maxOf(from, now) >= WORTH) spans += QuietSpan(run.id, run.todoId, from, stop)
        }

        // The current phase counts down by the server's word, whatever this phone's clock makes of its start.
        add(current.type, minOf(current.startedAt ?: now, now), end)
        if (run.autoAdvance) {
            for (phase in phases.drop(run.currentPhaseIndex + 1)) {
                if (run.finishAt?.let { end >= it } == true) break
                val next = end + phase.durationMinutes.minutes
                add(phase.type, end, next)
                end = next
            }
        }
        return spans
    }

    /** The phase the phone is quiet for at [now]: of several sessions at once, the one that ends last. Null: sound. */
    fun current(spans: List<QuietSpan>, now: Instant): QuietSpan? =
        spans.filter { it.from - BEGINS_WITHIN <= now && now < it.soundBackAt }.maxByOrNull { it.until }

    /** The next moment the phone changes: its sound back, or else the next focus phase beginning. Null: nothing ahead. */
    fun nextChange(spans: List<QuietSpan>, now: Instant): Instant? =
        current(spans, now)?.soundBackAt ?: spans.filter { it.from - BEGINS_WITHIN > now }.minOfOrNull { it.from }
}
