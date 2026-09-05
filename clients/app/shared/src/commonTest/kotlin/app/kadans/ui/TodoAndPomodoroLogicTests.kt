package app.kadans.ui

import app.kadans.api.model.Frequency
import app.kadans.api.model.PomodoroRunResponse
import app.kadans.api.model.PomodoroRunStatus
import app.kadans.ui.pomodoro.PomodoroViewModel
import app.kadans.ui.todos.CreateTodoUiState
import app.kadans.ui.todos.CreateTodoViewModel
import app.kadans.ui.todos.TodoMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

class TodoAndPomodoroLogicTests {

    private val portAuPrince = TimeZone.of("America/Port-au-Prince")

    private fun state() = CreateTodoUiState(
        title = "Standup",
        mode = TodoMode.Recurring,
        date = LocalDate(2027, 1, 4),
        time = LocalTime(9, 0),
        frequency = Frequency.Daily,
        interval = 2,
        count = 5,
    )

    @Test
    fun picked_wall_clock_time_becomes_an_instant_in_the_users_zone() {
        // 09:00 in Port-au-Prince (UTC-5 in January) = 14:00Z
        val request = CreateTodoViewModel.buildRecurring(state(), portAuPrince)

        assertEquals(Instant.parse("2027-01-04T14:00:00Z"), request.recurrenceRule.startDate)
        assertEquals("America/Port-au-Prince", request.recurrenceRule.timeZone)
        assertEquals(2, request.recurrenceRule.interval)
        assertEquals(5, request.recurrenceRule.count)
    }

    @Test
    fun three_times_a_day_becomes_a_byHour_list_with_one_minute() {
        val withTimes = state().copy(
            interval = 1,
            times = listOf(LocalTime(14, 0), LocalTime(8, 0), LocalTime(20, 0)),
        )

        val rule = CreateTodoViewModel.buildRecurring(withTimes, portAuPrince).recurrenceRule

        assertEquals(listOf(8, 14, 20), rule.byHour)
        assertEquals(listOf(0), rule.byMinute)
        // The start anchors on the earliest time of the day: 08:00 -05:00 = 13:00Z.
        assertEquals(Instant.parse("2027-01-04T13:00:00Z"), rule.startDate)
    }

    @Test
    fun mismatched_minutes_block_submission() {
        val bad = state().copy(times = listOf(LocalTime(8, 0), LocalTime(14, 30)))
        assertEquals(false, bad.timesShareMinute)
        assertEquals(false, bad.copy(title = "x").canSubmit)
        assertEquals(true, state().copy(times = listOf(LocalTime(8, 15), LocalTime(20, 15))).timesShareMinute)
    }

    @Test
    fun every_label_reads_like_a_sentence() {
        assertEquals("Every day", CreateTodoViewModel.everyLabel(Frequency.Daily, 1))
        assertEquals("Every 2 hours", CreateTodoViewModel.everyLabel(Frequency.Hourly, 2))
        assertEquals("Every 3 weeks", CreateTodoViewModel.everyLabel(Frequency.Weekly, 3))
    }

    @Test
    fun one_time_request_carries_the_due_instant() {
        val request = CreateTodoViewModel.buildOneTime(state().copy(mode = TodoMode.OneTime), portAuPrince)
        assertEquals(Instant.parse("2027-01-04T14:00:00Z"), request.dueDate)
    }

    private fun run(status: PomodoroRunStatus, endsAt: Instant? = null, pausedRemaining: Int? = null) =
        PomodoroRunResponse(
            id = "r", todoId = "t", status = status, currentPhaseIndex = 0,
            phaseEndsAt = endsAt, pausedRemainingSeconds = pausedRemaining,
            startedAt = Instant.parse("2027-01-01T12:00:00Z"), updatedAt = Instant.parse("2027-01-01T12:00:00Z"),
        )

    @Test
    fun active_run_counts_down_to_phaseEndsAt() {
        val now = Instant.parse("2027-01-01T12:10:00Z")
        val remaining = PomodoroViewModel.remainingOf(run(PomodoroRunStatus.Active, endsAt = Instant.parse("2027-01-01T12:25:00Z")), now)
        assertEquals(15.minutes, remaining)
    }

    @Test
    fun overdue_active_run_clamps_to_zero() {
        val now = Instant.parse("2027-01-01T13:00:00Z")
        val remaining = PomodoroViewModel.remainingOf(run(PomodoroRunStatus.Active, endsAt = Instant.parse("2027-01-01T12:25:00Z")), now)
        assertEquals(0.seconds, remaining)
    }

    @Test
    fun paused_run_shows_the_frozen_remainder() {
        val remaining = PomodoroViewModel.remainingOf(run(PomodoroRunStatus.Paused, pausedRemaining = 901), Instant.parse("2027-06-01T00:00:00Z"))
        assertEquals(901.seconds, remaining)
    }

    @Test
    fun countdown_formats_as_minutes_and_seconds() {
        assertEquals("25:00", PomodoroViewModel.format(25.minutes))
        assertEquals("4:05", PomodoroViewModel.format(4.minutes + 5.seconds))
        assertEquals("0:09", PomodoroViewModel.format(9.seconds))
    }
}
