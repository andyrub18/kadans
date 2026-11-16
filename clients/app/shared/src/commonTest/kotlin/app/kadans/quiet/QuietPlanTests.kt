package app.kadans.quiet

import app.kadans.api.model.PomodoroPhaseType
import app.kadans.api.model.PomodoroPhaseType.Break
import app.kadans.api.model.PomodoroPhaseType.Focus
import app.kadans.api.model.PomodoroRunPhaseResponse
import app.kadans.api.model.PomodoroRunResponse
import app.kadans.api.model.PomodoroRunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** A session's phases, each with its minutes: the classic cycle is focus 25, break 5, focus 25, break 15. */
internal val CLASSIC = listOf(Focus to 25, Break to 5, Focus to 25, Break to 15)

internal val NINE: Instant = Instant.parse("2026-11-16T14:00:00Z")

/**
 * A session as the server describes it: started at [startedAt], now in the phase [index], which ends at [endsAt]
 * (null: paused, or over).
 */
internal fun session(
    id: String = "run-1",
    phases: List<Pair<PomodoroPhaseType, Int>> = CLASSIC,
    index: Int = 0,
    endsAt: Instant? = NINE + 25.minutes,
    status: PomodoroRunStatus = PomodoroRunStatus.Active,
    handsFree: Boolean = true,
    finishAt: Instant? = NINE + 720.minutes,
    updatedAt: Instant = NINE,
    startedAt: Instant = NINE,
) = PomodoroRunResponse(
    id = id,
    todoId = "todo-$id",
    status = status,
    currentPhaseIndex = index,
    phaseEndsAt = endsAt,
    autoAdvance = handsFree,
    loop = true,
    cycleLength = phases.size,
    phases = phases.mapIndexed { i, (type, minutes) ->
        PomodoroRunPhaseResponse(id = "p$i", order = i, type = type, durationMinutes = minutes, startedAt = if (i == index) endsAt?.minus(minutes.minutes) else null)
    },
    startedAt = startedAt,
    updatedAt = updatedAt,
    finishAt = finishAt,
)

/** When a session's focus phases count down (ARCHITECTURE → "Do Not Disturb during focus"): what the phone is quiet for. */
class QuietPlanTests {
    private fun List<QuietSpan>.minutes() = map { (it.from - NINE).inWholeMinutes to (it.until - NINE).inWholeMinutes }

    @Test
    fun a_hands_free_session_is_known_to_the_end_of_its_cycle() {
        // Focus 9:00 to 9:25 and 9:30 to 9:55; after the long break the session waits for the person: nothing more.
        assertEquals(listOf(0L to 25L, 30L to 55L), QuietPlan.of(session(), NINE).minutes())
    }

    @Test
    fun a_manual_session_is_known_for_its_current_phase_only() {
        // It waits at 0:00 after every phase: when the next one begins is the person's to say.
        assertEquals(listOf(0L to 25L), QuietPlan.of(session(handsFree = false), NINE).minutes())
    }

    @Test
    fun a_break_is_not_quiet_and_the_focus_after_it_is() {
        val inBreak = session(index = 1, endsAt = NINE + 30.minutes)
        assertEquals(listOf(30L to 55L), QuietPlan.of(inBreak, NINE + 26.minutes).minutes())
        // The same break of a manual session: nothing is known to come.
        assertEquals(emptyList(), QuietPlan.of(inBreak.copy(autoAdvance = false), NINE + 26.minutes))
    }

    @Test
    fun a_session_paused_or_over_is_never_quiet() {
        assertEquals(emptyList(), QuietPlan.of(session(status = PomodoroRunStatus.Paused, endsAt = null), NINE))
        assertEquals(emptyList(), QuietPlan.of(session(status = PomodoroRunStatus.Completed, endsAt = null), NINE))
        assertEquals(emptyList(), QuietPlan.of(session(status = PomodoroRunStatus.Cancelled, endsAt = null), NINE))
    }

    @Test
    fun nothing_is_quiet_past_the_sessions_own_end() {
        // The session ends at 9:40 (its end time, or the todo's next occurrence): the second focus phase is cut there.
        assertEquals(listOf(0L to 25L, 30L to 40L), QuietPlan.of(session(finishAt = NINE + 40.minutes), NINE).minutes())
        // And one that ends during the first break never reaches the second.
        assertEquals(listOf(0L to 25L), QuietPlan.of(session(finishAt = NINE + 28.minutes), NINE).minutes())
    }

    @Test
    fun a_phase_nearly_over_is_not_worth_going_quiet_for() {
        // Eleven seconds left: the sound would be back in nine. Not for that.
        assertEquals(listOf(30L to 55L), QuietPlan.of(session(), NINE + 25.minutes - 11.seconds).minutes())
        // Thirteen seconds left: quiet for eleven.
        assertEquals(listOf(0L to 25L, 30L to 55L), QuietPlan.of(session(), NINE + 25.minutes - 13.seconds).minutes())
    }

    @Test
    fun the_sound_comes_back_a_moment_before_the_phase_ends() {
        val spans = QuietPlan.of(session(), NINE)
        // Quiet from the start, until two seconds before 9:25: what Kadans says at 9:25 is heard.
        assertEquals(NINE + 25.minutes, QuietPlan.current(spans, NINE + 10.minutes)?.until)
        assertEquals(NINE + 25.minutes - 2.seconds, QuietPlan.nextChange(spans, NINE + 10.minutes))
        assertNull(QuietPlan.current(spans, NINE + 25.minutes - 2.seconds))
        // In the break: the next change is the focus phase beginning at 9:30; after the cycle, none.
        assertEquals(NINE + 30.minutes, QuietPlan.nextChange(spans, NINE + 26.minutes))
        assertEquals(NINE + 55.minutes - 2.seconds, QuietPlan.nextChange(spans, NINE + 31.minutes))
        assertNull(QuietPlan.nextChange(spans, NINE + 56.minutes))
    }

    @Test
    fun a_phase_the_server_just_started_has_begun_whatever_this_phones_clock_says() {
        // The phone's clock is behind the server's: the session's first phase "starts" 0.6 s from now, or even a minute.
        val behind = QuietPlan.of(session(), NINE - 600.milliseconds)
        assertEquals(NINE + 25.minutes, QuietPlan.current(behind, NINE - 600.milliseconds)?.until)
        val farBehind = QuietPlan.of(session(), NINE - 1.minutes)
        assertEquals(NINE + 25.minutes, QuietPlan.current(farBehind, NINE - 1.minutes)?.until)
    }

    @Test
    fun a_phase_that_begins_within_five_seconds_has_begun() {
        // Android rings no alarm nearer than five seconds: a start that near is not waited for.
        val spans = QuietPlan.of(session(), NINE)
        assertNull(QuietPlan.current(spans, NINE + 30.minutes - 6.seconds))
        assertEquals(NINE + 30.minutes, QuietPlan.nextChange(spans, NINE + 30.minutes - 6.seconds))
        assertEquals(NINE + 55.minutes, QuietPlan.current(spans, NINE + 30.minutes - 4.seconds)?.until)
        assertEquals(NINE + 55.minutes - 2.seconds, QuietPlan.nextChange(spans, NINE + 30.minutes - 4.seconds))
    }

    @Test
    fun of_two_sessions_at_once_the_one_that_ends_last_counts() {
        val long = session(id = "run-2", phases = listOf(Focus to 50, Break to 10), endsAt = NINE + 50.minutes)
        val spans = QuietPlan.of(session(), NINE) + QuietPlan.of(long, NINE)
        assertEquals("run-2", QuietPlan.current(spans, NINE + 10.minutes)?.runId)
        // The first session's break (9:25 to 9:30) is not a break for the phone: the other one still counts down.
        assertEquals("run-2", QuietPlan.current(spans, NINE + 27.minutes)?.runId)
        assertEquals(NINE + 55.minutes, QuietPlan.current(spans, NINE + 52.minutes)?.until)
    }
}
