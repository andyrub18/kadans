package app.kadans.ui

import app.kadans.api.model.OccurrenceStatus
import app.kadans.api.model.TodoOccurrenceResponse
import app.kadans.ui.home.Ticks
import app.kadans.ui.home.TodayProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** Home's day: how much of it is done, what comes next, and the circle that marks a todo done with a moment to take it back. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeProgressTests {
    private val zone = TimeZone.of("America/Port-au-Prince")
    private val today = LocalDate(2026, 11, 24)

    /** A time on the 24th in Port-au-Prince (UTC−5 in November). */
    private fun at(hour: Int, minute: Int = 0, day: Int = 24): Instant =
        Instant.parse("2026-11-${day}T${(hour + 5).toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}:00Z")

    private fun occurrence(title: String, at: Instant, status: OccurrenceStatus = OccurrenceStatus.Pending) =
        TodoOccurrenceResponse(id = "o-$title", todoId = "t-$title", todoTitle = title, scheduledAt = at, originalScheduledAt = at, status = status)

    @Test
    fun theDayCountsWhatIsDoneAndNamesWhatIsNext() {
        val day = listOf(
            occurrence("run", at(6), OccurrenceStatus.Completed),
            occurrence("report", at(10, 15)),
            occurrence("plants", at(15, 15)),
            occurrence("reading", at(18)),
        )
        val progress = TodayProgress.of(day, today, zone, now = at(9))

        assertEquals(1, progress.done)
        assertEquals(4, progress.total)
        assertEquals("report", progress.next?.todoTitle)
        assertEquals(0.25f, progress.fraction)
        assertFalse(progress.allDone)
    }

    @Test
    fun anotherDaysTodosAndCancelledOnesAreNotTheDays() {
        val list = listOf(
            occurrence("today", at(10)),
            occurrence("dropped", at(11), OccurrenceStatus.Cancelled),
            occurrence("tomorrow", at(10, day = 25)),
        )
        val progress = TodayProgress.of(list, today, zone, now = at(8))

        assertEquals(1, progress.total)
        assertEquals("today", progress.next?.todoTitle)
    }

    @Test
    fun aDayIsItsOwnInThePersonsZoneNotInUtc() {
        // 21:00 on the 24th in Port-au-Prince is 02:00 on the 25th in UTC: still today's.
        val late = occurrence("reading", Instant.parse("2026-11-25T02:00:00Z"))

        assertEquals(1, TodayProgress.of(listOf(late), today, zone, now = at(8)).total)
    }

    @Test
    fun withNothingAheadWhatIsNextIsTheEarliestLeftBehind() {
        val day = listOf(occurrence("morning", at(8)), occurrence("noon", at(12)))

        assertEquals("morning", TodayProgress.of(day, today, zone, now = at(17)).next?.todoTitle)
    }

    @Test
    fun aTickedTodoCountsAsDoneBeforeTheServerKnows() {
        val day = listOf(occurrence("report", at(10)), occurrence("plants", at(15)))
        val progress = TodayProgress.of(day, today, zone, now = at(9), ticked = setOf("o-report"))

        assertEquals(1, progress.done)
        assertEquals("plants", progress.next?.todoTitle)
    }

    @Test
    fun aDayWithEverythingDoneSaysSoAndAnEmptyDayDoesNot() {
        val done = listOf(occurrence("report", at(10), OccurrenceStatus.Completed))

        assertTrue(TodayProgress.of(done, today, zone, now = at(12)).allDone)
        assertNull(TodayProgress.of(done, today, zone, now = at(12)).next)
        val empty = TodayProgress.of(emptyList(), today, zone, now = at(12))
        assertFalse(empty.allDone)
        assertEquals(0f, empty.fraction)
    }

    @Test
    fun aTickIsSentOnlyAfterTheMomentToTakeItBack() = runTest {
        val sent = mutableListOf<String>()
        val ticks = Ticks(this, wait = 5.seconds, send = { sent += it })

        ticks.tick("a")
        runCurrent()
        assertEquals(setOf("a"), ticks.ticked.value)
        advanceTimeBy(4.seconds)
        assertTrue(sent.isEmpty())

        advanceUntilIdle()
        assertEquals(listOf("a"), sent)
        assertTrue(ticks.ticked.value.isEmpty())
    }

    @Test
    fun aTickTakenBackIsNeverSent() = runTest {
        val sent = mutableListOf<String>()
        val ticks = Ticks(this, wait = 5.seconds, send = { sent += it })

        ticks.tick("a")
        advanceTimeBy(2.seconds)
        assertTrue(ticks.undo("a"))
        advanceUntilIdle()

        assertTrue(sent.isEmpty())
        assertTrue(ticks.ticked.value.isEmpty())
        // Once sent, there is nothing left to take back.
        ticks.tick("b")
        advanceUntilIdle()
        assertFalse(ticks.undo("b"))
    }

    @Test
    fun aSecondPressWhileItWaitsSendsItOnce() = runTest {
        val sent = mutableListOf<String>()
        val ticks = Ticks(this, wait = 5.seconds, send = { sent += it })

        ticks.tick("a")
        ticks.tick("a")
        advanceUntilIdle()

        assertEquals(listOf("a"), sent)
    }

    @Test
    fun theCircleStaysTickedUntilTheListIsReadAgainAndAFailureIsTold() = runTest {
        val told = mutableListOf<Pair<String, Boolean>>()
        var tickedWhenTold: Set<String>? = null
        lateinit var ticks: Ticks
        ticks = Ticks(
            this,
            wait = 5.seconds,
            send = { if (it == "bad") error("offline") },
            sent = { id, failure ->
                told += id to (failure != null)
                tickedWhenTold = ticks.ticked.value
            },
        )

        ticks.tick("good")
        advanceUntilIdle()
        assertEquals(setOf("good"), tickedWhenTold)

        ticks.tick("bad")
        advanceUntilIdle()
        assertEquals(listOf("good" to false, "bad" to true), told)
        assertTrue(ticks.ticked.value.isEmpty())
    }

    @Test
    fun whatStillWaitsWhenTheScreenGoesIsHandedOverNotLost() = runTest {
        val sent = mutableListOf<String>()
        val ticks = Ticks(this, wait = 5.seconds, send = { sent += it })

        ticks.tick("a")
        ticks.tick("b")
        advanceTimeBy(1.seconds)
        val left = ticks.abandon()
        advanceUntilIdle()

        assertEquals(setOf("a", "b"), left.toSet())
        assertTrue(sent.isEmpty())
    }
}
