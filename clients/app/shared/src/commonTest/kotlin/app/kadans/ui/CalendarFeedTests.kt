package app.kadans.ui

import app.kadans.api.KadansApi
import app.kadans.api.model.DevicePlatform
import app.kadans.i18n.CreoleStrings
import app.kadans.i18n.EnglishStrings
import app.kadans.i18n.FrenchStrings
import app.kadans.realtime.RealtimeEvent
import app.kadans.sync.DataChanges
import app.kadans.ui.settings.CalendarFeedUiState
import app.kadans.ui.settings.CalendarFeedViewModel
import app.kadans.ui.settings.GOOGLE_CALENDAR_ADD_BY_URL
import app.kadans.ui.settings.addsToGoogleHere
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Settings → My todos in my calendar (ARCHITECTURE → "The calendar feed"): the page shows the feed as the server holds
 * it, whatever device changed it, and hands the link to a calendar as an https address only.
 */
class CalendarFeedTests {
    private val dispatcher = StandardTestDispatcher()
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest
    fun before() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun after() = Dispatchers.resetMain()

    /** The server: the account's feed, off or on with link number [link]; [reachable] false: offline. */
    private inner class Server {
        var link: Int? = null
        var lastFetchedAt: String? = null
        var reachable = true

        /** False: the account has no phone subscription, which the feed is part of. */
        var subscribed = true
        val asked = mutableListOf<String>()

        /** Every call, answered or not. */
        var attempts = 0

        private fun feed() = link?.let {
            val read = lastFetchedAt?.let { at -> "\"$at\"" } ?: "null"
            """{"enabled":true,"url":"https://api.test/calendar/feed/secret-$it/kadans.ics","createdAt":"2026-11-13T10:00:00Z",""" +
                """"lastFetchedAt":$read,"available":$subscribed}"""
        } ?: """{"enabled":false,"url":null,"createdAt":null,"lastFetchedAt":null,"available":$subscribed}"""

        private val refused = """{"title":"Subscription required","status":402,"detail":"The calendar link is part of the phone subscription.","errorCode":"10059"}"""

        val api: KadansApi = KadansApi.create(
            "http://test",
            engine = MockEngine { request ->
                attempts++
                check(reachable) { "offline" }
                val call = "${request.method.value} ${request.url.encodedPath}"
                asked += call
                when (call) {
                    "GET /calendar-feed" -> respond(feed(), HttpStatusCode.OK, jsonHeaders)
                    "PUT /calendar-feed" -> if (!subscribed) respond(refused, HttpStatusCode.PaymentRequired, jsonHeaders) else {
                        link = link ?: 1
                        respond(feed(), HttpStatusCode.OK, jsonHeaders)
                    }
                    "POST /calendar-feed/reset" -> if (!subscribed) respond(refused, HttpStatusCode.PaymentRequired, jsonHeaders) else {
                        link = (link ?: 0) + 1
                        lastFetchedAt = null
                        respond(feed(), HttpStatusCode.OK, jsonHeaders)
                    }
                    "DELETE /calendar-feed" -> {
                        link = null
                        respond("", HttpStatusCode.NoContent)
                    }
                    else -> respond("""{"title":"Not found","status":404}""", HttpStatusCode.NotFound, jsonHeaders)
                }
            },
        )
    }

    private suspend fun CalendarFeedViewModel.showing(what: (CalendarFeedUiState) -> Boolean) = state.first(what)

    private suspend fun CalendarFeedViewModel.loaded() = showing { !it.isLoading && !it.isBusy }

    /** The page as first shown, listening for changes: a signal sent before it listens is not for it. */
    private suspend fun TestScope.opened(viewModel: CalendarFeedViewModel): CalendarFeedUiState {
        val shown = viewModel.loaded()
        runCurrent()
        return shown
    }

    @Test
    fun off_at_first_then_on_with_its_link() = runTest(dispatcher) {
        val server = Server()
        val viewModel = CalendarFeedViewModel(server.api, DataChanges(MutableSharedFlow()))
        val off = viewModel.loaded()
        assertEquals(false, off.feed?.enabled)
        assertNull(off.feed?.url)

        viewModel.turnOn()
        val on = viewModel.showing { it.feed?.enabled == true && !it.isBusy }

        assertEquals("https://api.test/calendar/feed/secret-1/kadans.ics", on.feed?.url)
        assertNull(on.feed?.lastFetchedAt)
        assertEquals(listOf("GET /calendar-feed", "PUT /calendar-feed"), server.asked)
    }

    @Test
    fun replacing_the_link_shows_the_new_one_and_turning_off_shows_off() = runTest(dispatcher) {
        val server = Server().apply { link = 1; lastFetchedAt = "2026-11-13T14:02:00Z" }
        val viewModel = CalendarFeedViewModel(server.api, DataChanges(MutableSharedFlow()))
        val before = viewModel.loaded()
        assertEquals("2026-11-13T14:02:00Z", before.feed?.lastFetchedAt.toString())

        viewModel.replaceLink()
        val replaced = viewModel.showing { it.feed?.url?.contains("secret-2") == true && !it.isBusy }
        assertNotEquals(before.feed?.url, replaced.feed?.url)
        assertNull(replaced.feed?.lastFetchedAt) // no calendar has read the new one

        viewModel.turnOff()
        val off = viewModel.showing { it.feed?.enabled == false && !it.isBusy }
        assertNull(off.feed?.url)
        assertEquals("DELETE /calendar-feed", server.asked.last())
    }

    @Test
    fun turned_on_from_another_device_shows_here_without_asking() = runTest(dispatcher) {
        val server = Server()
        val hub = MutableSharedFlow<RealtimeEvent>()
        val viewModel = CalendarFeedViewModel(server.api, DataChanges(hub))
        opened(viewModel)

        server.link = 7 // the phone turned it on
        hub.emit(RealtimeEvent.DataChanged(setOf("todos"))) // what the server says after any change to the feed

        assertEquals("https://api.test/calendar/feed/secret-7/kadans.ics", viewModel.showing { it.feed?.enabled == true }.feed?.url)
    }

    @Test
    fun back_in_front_the_page_says_a_calendar_read_the_link_meanwhile() = runTest(dispatcher) {
        val server = Server().apply { link = 1 }
        val changes = DataChanges(MutableSharedFlow())
        val viewModel = CalendarFeedViewModel(server.api, changes)
        assertNull(opened(viewModel).feed?.lastFetchedAt)

        server.lastFetchedAt = "2026-11-13T14:02:00Z" // Google read it while the app was in the background
        changes.cameBack()

        assertEquals("2026-11-13T14:02:00Z", viewModel.showing { it.feed?.lastFetchedAt != null }.feed?.lastFetchedAt.toString())
    }

    @Test
    fun without_the_server_the_page_says_so_and_asks_again() = runTest(dispatcher) {
        val server = Server().apply { reachable = false }
        val viewModel = CalendarFeedViewModel(server.api, DataChanges(MutableSharedFlow()))
        val failed = viewModel.loaded()
        assertNull(failed.feed)
        assertEquals("network", failed.errorCode)

        server.reachable = true
        viewModel.load()

        val shown = viewModel.showing { it.feed != null }
        assertEquals(false, shown.feed?.enabled)
        assertNull(shown.errorCode)
    }

    @Test
    fun a_quiet_reload_that_fails_keeps_what_is_shown() = runTest(dispatcher) {
        val server = Server().apply { link = 1 }
        val changes = DataChanges(MutableSharedFlow())
        val viewModel = CalendarFeedViewModel(server.api, changes)
        opened(viewModel)

        server.reachable = false
        changes.cameBack()
        // The server was asked again and could not answer.
        withContext(Dispatchers.Default) { withTimeout(10_000) { while (server.attempts < 2) delay(10) } }
        runCurrent()

        val shown = viewModel.state.value
        assertEquals(2, server.attempts)
        assertEquals("https://api.test/calendar/feed/secret-1/kadans.ics", shown.feed?.url)
        assertNull(shown.errorCode)
    }

    @Test
    fun a_change_that_fails_says_so_and_keeps_the_feed_as_it_was() = runTest(dispatcher) {
        val server = Server().apply { link = 1 }
        val viewModel = CalendarFeedViewModel(server.api, DataChanges(MutableSharedFlow()))
        viewModel.loaded()

        server.reachable = false
        viewModel.turnOff()
        val failed = viewModel.showing { it.errorCode != null }

        assertEquals("network", failed.errorCode)
        assertEquals(true, failed.feed?.enabled)
        assertFalse(failed.isBusy)
    }

    @Test
    fun without_the_phone_subscription_the_page_says_so_and_nothing_turns_on() = runTest(dispatcher) {
        // The free desktop app would otherwise be a way around the subscription: todos on the phone, in its calendar.
        val server = Server().apply { subscribed = false }
        val viewModel = CalendarFeedViewModel(server.api, DataChanges(MutableSharedFlow()))
        val shown = viewModel.loaded()
        assertEquals(false, shown.feed?.available)
        assertEquals(false, shown.feed?.enabled)

        // Asked anyway (the subscription ended since the page was loaded): the server refuses, and the page says why.
        viewModel.turnOn()
        val refused = viewModel.showing { it.errorCode != null }

        assertEquals("10059", refused.errorCode)
        assertEquals(false, refused.feed?.enabled)
        assertEquals(EnglishStrings.calendarFeed.needsSubscription, EnglishStrings.errorFor(refused.errorCode, refused.error))
        assertEquals(FrenchStrings.calendarFeed.needsSubscription, FrenchStrings.errorFor(refused.errorCode, refused.error))
        assertEquals(CreoleStrings.calendarFeed.needsSubscription, CreoleStrings.errorFor(refused.errorCode, refused.error))
    }

    @Test
    fun a_link_kept_through_a_lapsed_subscription_can_still_be_turned_off() = runTest(dispatcher) {
        val server = Server().apply { link = 1; subscribed = false }
        val viewModel = CalendarFeedViewModel(server.api, DataChanges(MutableSharedFlow()))
        val paused = viewModel.loaded()
        assertEquals(true, paused.feed?.enabled)
        assertEquals(false, paused.feed?.available)

        viewModel.turnOff()

        assertEquals(false, viewModel.showing { it.feed?.enabled == false && !it.isBusy }.feed?.enabled)
        assertNull(server.link)
    }

    @Test
    fun the_subscription_back_the_page_offers_the_feed_again() = runTest(dispatcher) {
        val server = Server().apply { link = 1; subscribed = false }
        val changes = DataChanges(MutableSharedFlow())
        val viewModel = CalendarFeedViewModel(server.api, changes)
        assertEquals(false, opened(viewModel).feed?.available)

        server.subscribed = true // subscribed on the phone
        changes.cameBack()

        val back = viewModel.showing { it.feed?.available == true }
        assertEquals("https://api.test/calendar/feed/secret-1/kadans.ics", back.feed?.url) // the same link as before
    }

    @Test
    fun google_calendar_takes_a_link_on_a_computer_only() {
        // Its phone apps cannot add a calendar from a link: a phone gets the explanation, a computer the button.
        assertFalse(addsToGoogleHere(DevicePlatform.Android))
        assertFalse(addsToGoogleHere(DevicePlatform.Ios))
        assertTrue(addsToGoogleHere(DevicePlatform.Linux))
        assertTrue(addsToGoogleHere(DevicePlatform.Windows))
        assertTrue(addsToGoogleHere(DevicePlatform.MacOs))
        assertTrue(GOOGLE_CALENDAR_ADD_BY_URL.startsWith("https://calendar.google.com/"))
    }

    @Test
    fun each_language_says_when_a_calendar_last_read_the_link() {
        assertEquals("Last read by a calendar: Today · 14:02", EnglishStrings.calendarFeed.lastRead("Today · 14:02"))
        assertEquals("Dernière lecture par un calendrier : Aujourd'hui · 14:02", FrenchStrings.calendarFeed.lastRead("Aujourd'hui · 14:02"))
        assertEquals("Dènye fwa yon kalandriye li l: Jodi a · 14:02", CreoleStrings.calendarFeed.lastRead("Jodi a · 14:02"))
    }
}
