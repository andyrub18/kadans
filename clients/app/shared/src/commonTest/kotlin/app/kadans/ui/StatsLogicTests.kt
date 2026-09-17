package app.kadans.ui

import app.kadans.api.model.PomodoroDayStats
import app.kadans.api.model.PomodoroPhaseType
import app.kadans.api.model.PomodoroRunPhaseResponse
import app.kadans.api.model.PomodoroRunResponse
import app.kadans.api.model.PomodoroRunStatus
import app.kadans.api.model.PomodoroStatsResponse
import app.kadans.ui.stats.StatsViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

class StatsLogicTests {
    private val today = LocalDate(2026, 9, 17)
    private val at = Instant.parse("2026-09-17T12:00:00Z")

    private fun stats(vararg days: PomodoroDayStats) = PomodoroStatsResponse(
        from = at, to = at, timeZoneId = "America/Port-au-Prince",
        completedRuns = days.sumOf { it.completedRuns }, cancelledRuns = 0,
        focusMinutes = days.sumOf { it.focusMinutes }, breakMinutes = days.sumOf { it.breakMinutes },
        perDay = days.toList(),
    )

    @Test
    fun idle_days_become_zeros_so_the_calendar_has_no_holes() {
        val days = StatsViewModel.fillDays(listOf(PomodoroDayStats(LocalDate(2026, 9, 15), 50, 10, 1)), today, dayCount = 7)

        assertEquals(7, days.size)
        assertEquals(LocalDate(2026, 9, 11), days.first().date)
        assertEquals(today, days.last().date)
        assertEquals(listOf(0, 0, 0, 0, 50, 0, 0), days.map { it.focusMinutes })
    }

    @Test
    fun a_range_crossing_a_month_boundary_stays_continuous() {
        val days = StatsViewModel.fillDays(emptyList(), LocalDate(2026, 3, 2), dayCount = 4)

        assertEquals(listOf("2026-02-27", "2026-02-28", "2026-03-01", "2026-03-02"), days.map { it.date.toString() })
    }

    @Test
    fun the_average_counts_idle_days_and_the_best_day_ignores_them() {
        val summary = StatsViewModel.summarize(
            stats(PomodoroDayStats(LocalDate(2026, 9, 15), 50, 10, 1), PomodoroDayStats(LocalDate(2026, 9, 17), 90, 20, 2)),
            today, dayCount = 7,
        )

        assertEquals(20, summary.dailyAverageFocusMinutes) // 140 minutes over 7 days, not over 2
        assertEquals(LocalDate(2026, 9, 17), summary.bestDay?.date)
        assertEquals(90, summary.maxFocusMinutes)
    }

    @Test
    fun an_empty_period_has_no_best_day() {
        val summary = StatsViewModel.summarize(stats(), today, dayCount = 30)

        assertEquals(null, summary.bestDay)
        assertEquals(0, summary.dailyAverageFocusMinutes)
        assertEquals(30, summary.days.size)
    }

    @Test
    fun durations_read_the_same_in_every_language() {
        assertEquals("0 min", StatsViewModel.formatMinutes(0))
        assertEquals("45 min", StatsViewModel.formatMinutes(45))
        assertEquals("2 h", StatsViewModel.formatMinutes(120))
        assertEquals("2 h 05 min", StatsViewModel.formatMinutes(125))
    }

    private fun phase(order: Int, type: PomodoroPhaseType, minutes: Int, done: Boolean) =
        PomodoroRunPhaseResponse("p$order", order, type, minutes, startedAt = at, completedAt = if (done) at else null)

    @Test
    fun only_completed_focus_phases_count_across_every_lap() {
        val run = PomodoroRunResponse(
            id = "r1", todoId = "t1", status = PomodoroRunStatus.Cancelled, currentPhaseIndex = 3,
            loop = true, cycleLength = 2, startedAt = at, updatedAt = at,
            phases = listOf(
                phase(0, PomodoroPhaseType.Focus, 25, done = true),
                phase(1, PomodoroPhaseType.Break, 5, done = true),
                phase(2, PomodoroPhaseType.Focus, 25, done = true),
                phase(3, PomodoroPhaseType.Break, 5, done = false), // ended here
            ),
        )

        assertEquals(50, StatsViewModel.focusMinutesOf(run))
        assertEquals(2, StatsViewModel.lapsOf(run))
    }

    @Test
    fun a_run_without_cycle_information_is_one_lap() {
        val run = PomodoroRunResponse(id = "r", todoId = "t", status = PomodoroRunStatus.Active, currentPhaseIndex = 0, startedAt = at, updatedAt = at)

        assertEquals(1, StatsViewModel.lapsOf(run))
        assertEquals(0, StatsViewModel.focusMinutesOf(run))
    }
}
