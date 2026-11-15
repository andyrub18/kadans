package app.kadans.widget

import app.kadans.api.model.ClockFormat
import app.kadans.i18n.CreoleStrings
import app.kadans.i18n.EnglishStrings
import app.kadans.i18n.FrenchStrings
import app.kadans.i18n.StringsCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * What the home-screen widget shows (ARCHITECTURE → "The home-screen widget"): today's pending todos as the phone's
 * day and the account's clock write them, what replaces them, and what happens when they do not all fit.
 */
class TodayWidgetContentTests {
    private val haiti = TimeZone.of("America/Port-au-Prince") // UTC-5 in November
    private val now = Instant.parse("2026-11-13T15:30:00Z") // Friday, 10:30 there

    /** A pending time of a todo, its occurrence named after its title ("Call the bank" → "occ:Call the bank"). */
    private fun at(time: String, title: String, id: String = title) = WidgetEntry(id, title, Instant.parse(time), "occ:$title")

    private fun content(
        entries: List<WidgetEntry>?,
        capacity: Int = 4,
        gap: WidgetGap? = null,
        catalog: StringsCatalog = EnglishStrings,
        clock: ClockFormat = ClockFormat.TwentyFourHour,
        zone: TimeZone = haiti,
        doneAt: Map<String, Instant> = emptyMap(),
    ) = TodayWidgetContent.of(entries, gap, now, zone, catalog, clock, capacity, doneAt)

    /** The lines as read: a todo with a circle starts with "○", one ticked and waiting with "✓" and ends with "(undo)". */
    private fun TodayWidgetContent.text() = lines.map {
        when (it) {
            is WidgetLine.Todo -> (if (it.undo) "✓ " else if (it.done != null) "○ " else "") + "${it.time} ${it.title}" + (if (it.undo) " (undo)" else "")
            is WidgetLine.Note -> "(${it.text})"
            is WidgetLine.Label -> "[${it.text}]"
            is WidgetLine.More -> "+ ${it.text}"
        }
    }

    @Test
    fun todays_pending_todos_soonest_first_under_today_and_the_date() {
        val shown = content(
            listOf(
                at("2026-11-13T23:00:00Z", "Call the bank"),
                at("2026-11-13T14:00:00Z", "Morning run"), // 09:00, already past and still pending: it stays
                at("2026-11-14T14:00:00Z", "Tomorrow's run"),
            ),
        )

        assertEquals("Today", shown.title)
        assertEquals("Fri, Nov 13", shown.date)
        assertEquals(listOf("○ 09:00 Morning run", "○ 18:00 Call the bank", "[Tomorrow]", "09:00 Tomorrow's run"), shown.text())
    }

    @Test
    fun todays_lines_have_a_circle_that_knows_its_occurrence_and_tomorrows_have_none() {
        val lines = content(listOf(at("2026-11-13T23:00:00Z", "Call the bank"), at("2026-11-14T14:00:00Z", "Morning run"))).lines

        assertEquals("occ:Call the bank", (lines[0] as WidgetLine.Todo).done)
        assertEquals(null, (lines[2] as WidgetLine.Todo).done) // not come yet: nothing to mark done, as in a reminder
    }

    @Test
    fun a_time_the_server_only_computed_has_no_circle() {
        val computed = WidgetEntry("t", "Far ahead", Instant.parse("2026-11-13T23:00:00Z"), occurrenceId = null)

        assertEquals(listOf("18:00 Far ahead"), content(listOf(computed)).text())
    }

    @Test
    fun a_circle_just_tapped_strikes_its_line_and_offers_undo_until_its_moment() {
        val entries = listOf(at("2026-11-13T22:00:00Z", "Call the bank"), at("2026-11-13T23:00:00Z", "Groceries"))
        val inThreeSeconds = Instant.parse("2026-11-13T15:30:03Z")

        assertEquals(
            listOf("✓ 17:00 Call the bank (undo)", "○ 18:00 Groceries"),
            content(entries, doneAt = mapOf("occ:Call the bank" to inThreeSeconds)).text(),
        )
    }

    @Test
    fun once_its_moment_has_passed_the_line_is_gone_sent_or_not() {
        // Sent, or still waiting for a network: either way the person was told it is done.
        val entries = listOf(at("2026-11-13T22:00:00Z", "Call the bank"), at("2026-11-13T23:00:00Z", "Groceries"))
        val aSecondAgo = Instant.parse("2026-11-13T15:29:59Z")

        assertEquals(listOf("○ 18:00 Groceries"), content(entries, doneAt = mapOf("occ:Call the bank" to aSecondAgo)).text())
        assertEquals(listOf("○ 18:00 Groceries"), content(entries, doneAt = mapOf("occ:Call the bank" to now)).text())
        // The last one of the day done: the widget says so.
        assertEquals(listOf("(Nothing left today)"), content(entries.take(1), doneAt = mapOf("occ:Call the bank" to aSecondAgo)).text())
    }

    @Test
    fun tomorrows_follow_todays_only_while_there_is_room_for_their_label_and_one_of_them() {
        val entries = listOf(
            at("2026-11-13T23:00:00Z", "Call the bank"),
            at("2026-11-14T14:00:00Z", "Morning run"),
            at("2026-11-14T20:00:00Z", "Groceries"),
            at("2026-11-14T22:00:00Z", "Call home"),
        )

        assertEquals(listOf("○ 18:00 Call the bank"), content(entries, capacity = 1).text())
        assertEquals(listOf("○ 18:00 Call the bank"), content(entries, capacity = 2).text()) // a label alone says nothing
        assertEquals(listOf("○ 18:00 Call the bank", "[Tomorrow]", "09:00 Morning run"), content(entries.take(2), capacity = 3).text())
        // One line under the label, three todos for it: "Tomorrow" over "3 more" would name none of them.
        assertEquals(listOf("○ 18:00 Call the bank"), content(entries, capacity = 3).text())
        assertEquals(listOf("○ 18:00 Call the bank", "[Tomorrow]", "09:00 Morning run", "+ 2 more"), content(entries, capacity = 4).text())
        assertEquals(listOf("○ 18:00 Call the bank", "[Tomorrow]", "09:00 Morning run", "15:00 Groceries", "17:00 Call home"), content(entries, capacity = 8).text())
    }

    @Test
    fun todays_that_do_not_all_fit_leave_no_room_for_tomorrows() {
        val entries = (1..5).map { at("2026-11-13T${15 + it}:00:00Z", "Todo $it") } + at("2026-11-14T14:00:00Z", "Morning run")

        assertEquals(listOf("○ 11:00 Todo 1", "○ 12:00 Todo 2", "○ 13:00 Todo 3", "+ 2 more"), content(entries, capacity = 4).text())
        assertEquals(listOf("○ 11:00 Todo 1", "○ 12:00 Todo 2", "○ 13:00 Todo 3", "○ 14:00 Todo 4", "○ 15:00 Todo 5"), content(entries, capacity = 6).text())
        assertEquals(
            listOf("○ 11:00 Todo 1", "○ 12:00 Todo 2", "○ 13:00 Todo 3", "○ 14:00 Todo 4", "○ 15:00 Todo 5", "[Tomorrow]", "09:00 Morning run"),
            content(entries, capacity = 7).text(),
        )
    }

    @Test
    fun a_tap_on_a_line_knows_its_todo() {
        val line = content(listOf(at("2026-11-13T23:00:00Z", "Call the bank", id = "todo-7"))).lines.single() as WidgetLine.Todo

        assertEquals("todo-7", line.todoId)
    }

    @Test
    fun the_hours_follow_the_accounts_clock_and_the_words_the_apps_language() {
        val entries = listOf(at("2026-11-13T23:30:00Z", "Appeler la banque"))

        assertEquals(listOf("○ 6:30 PM Appeler la banque"), content(entries, clock = ClockFormat.TwelveHour).text())
        val french = content(entries, catalog = FrenchStrings)
        assertEquals("Aujourd'hui", french.title)
        assertEquals(listOf("○ 18:30 Appeler la banque"), french.text())
        assertEquals("Jodi a", content(entries, catalog = CreoleStrings).title)
    }

    @Test
    fun today_is_the_phones_day_not_utcs() {
        // 22:00 on Friday in Port-au-Prince is already Saturday 03:00 in UTC: still today's, there.
        val late = listOf(at("2026-11-14T03:00:00Z", "Late call"), at("2026-11-14T05:30:00Z", "After midnight"))

        assertEquals(listOf("○ 22:00 Late call", "[Tomorrow]", "00:30 After midnight"), content(late).text())
        // The same instants on a phone in Paris (Friday 16:30 there): both are Saturday's.
        assertEquals(listOf("(Nothing left today)", "[Tomorrow]", "04:00 Late call", "06:30 After midnight"), content(late, zone = TimeZone.of("Europe/Paris")).text())
    }

    @Test
    fun todos_that_do_not_fit_give_their_last_line_to_how_many_more() {
        val five = (1..5).map { at("2026-11-13T${15 + it}:00:00Z", "Todo $it") }

        assertEquals(listOf("○ 11:00 Todo 1", "○ 12:00 Todo 2", "○ 13:00 Todo 3", "+ 2 more"), content(five, capacity = 4).text())
        assertEquals(listOf("○ 11:00 Todo 1", "○ 12:00 Todo 2", "○ 13:00 Todo 3", "○ 14:00 Todo 4", "○ 15:00 Todo 5"), content(five, capacity = 5).text())
        assertEquals(listOf("+ 5 more"), content(five, capacity = 1).text())
        assertEquals(listOf("○ 11:00 Todo 1", "+ 4 de plus"), content(five, capacity = 2, catalog = FrenchStrings).text())
        assertEquals(listOf("○ 11:00 Todo 1", "+ 4 lòt ankò"), content(five, capacity = 2, catalog = CreoleStrings).text())
    }

    @Test
    fun with_nothing_left_today_it_says_so_and_goes_on_with_tomorrow() {
        val tomorrow = listOf(at("2026-11-14T14:00:00Z", "Morning run"), at("2026-11-14T20:00:00Z", "Groceries"), at("2026-11-14T22:00:00Z", "Call home"))

        assertEquals(listOf("(Nothing left today)", "[Tomorrow]", "09:00 Morning run", "+ 2 more"), content(tomorrow, capacity = 4).text())
        assertEquals(listOf("(Nothing left today)", "[Tomorrow]", "09:00 Morning run", "15:00 Groceries", "17:00 Call home"), content(tomorrow, capacity = 5).text())
        // Too short for the label and a todo under the sentence: the sentence alone.
        assertEquals(listOf("(Nothing left today)"), content(tomorrow, capacity = 2).text())
        assertEquals(listOf("(Nothing left today)"), content(tomorrow, capacity = 3).text())
        assertEquals(listOf("(Nothing left today)", "[Tomorrow]", "09:00 Morning run"), content(tomorrow.take(1), capacity = 3).text())
        assertEquals(listOf("(Nothing left today)"), content(emptyList()).text())
        assertEquals(listOf("(Plus rien aujourd'hui)", "[Demain]", "09:00 Morning run", "+ 2 de plus"), content(tomorrow, catalog = FrenchStrings).text())
    }

    @Test
    fun the_day_after_tomorrow_is_never_shown() {
        assertEquals(listOf("(Nothing left today)"), content(listOf(at("2026-11-15T14:00:00Z", "Sunday's"))).text())
    }

    @Test
    fun without_a_session_a_subscription_or_a_first_fetch_it_says_what_to_do() {
        val held = listOf(at("2026-11-13T23:00:00Z", "Call the bank"))

        assertEquals(listOf("(Sign in to Kadans to see your day)"), content(held, gap = WidgetGap.SignedOut).text())
        assertEquals(listOf("(Subscribe in Kadans to see your day here)"), content(held, gap = WidgetGap.Locked).text())
        assertEquals(listOf("(Open Kadans to load your day)"), content(null, gap = WidgetGap.NotLoaded).text())
        assertEquals(listOf("(Open Kadans to load your day)"), content(null).text())
        // The date stays above, whatever is under it.
        assertEquals("Fri, Nov 13", content(held, gap = WidgetGap.SignedOut).date)
    }

    @Test
    fun two_todos_at_the_same_time_keep_one_order() {
        val same = listOf(at("2026-11-13T23:00:00Z", "Zumba"), at("2026-11-13T23:00:00Z", "Abdos"))

        assertEquals(content(same).text(), content(same.reversed()).text())
        assertEquals(listOf("○ 18:00 Abdos", "○ 18:00 Zumba"), content(same).text())
    }
}
