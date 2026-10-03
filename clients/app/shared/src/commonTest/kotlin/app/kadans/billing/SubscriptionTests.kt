package app.kadans.billing

import app.kadans.api.AuthTokens
import app.kadans.api.InMemoryTokenStore
import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.api.model.BillingStore
import app.kadans.api.model.SubscriptionState
import app.kadans.api.model.SubscriptionStatusResponse
import app.kadans.i18n.CreoleStrings
import app.kadans.i18n.EnglishStrings
import app.kadans.i18n.FrenchStrings
import app.kadans.realtime.KadansRealtime
import app.kadans.ui.billing.PaywallExit
import app.kadans.ui.billing.PaywallNotice
import app.kadans.ui.billing.PaywallViewModel
import app.kadans.ui.billing.SubscriptionSettingsViewModel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * The phone's side of the subscription: the gate before Home (desktop is free; an unreachable server locks no one out;
 * the store's purchases are tried before the paywall), and the paywall's purchase, restore and fake trial. Access is
 * always the server's answer.
 */
class SubscriptionTests {
    private val dispatcher = StandardTestDispatcher()
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest
    fun before() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun after() = Dispatchers.resetMain()

    private fun status(required: Boolean = true, hasAccess: Boolean = false, state: String? = null, store: String? = null, fakeStore: Boolean = false) =
        """{"required":$required,"hasAccess":$hasAccess${state?.let { ""","state":"$it"""" } ?: ""}${store?.let { ""","store":"$it"""" } ?: ""},""" +
            """"expiresAt":"2026-10-17T12:00:00Z","autoRenewing":true,"accountHash":"hash-of-alice","googleProductId":"kadans_mobile","fakeStore":$fakeStore}"""

    private val blocked = status()
    private val paidUp = status(hasAccess = true, state = "Trial", store = "Google")
    private val otherAccount =
        """{"title":"Conflict","status":409,"detail":"This subscription belongs to another Kadans account.","errorCode":"10057"}"""

    /** Answers by "METHOD /path" (and the request body for purchases); records every call. */
    private class Server(var answer: (call: String, body: String) -> Pair<HttpStatusCode, String>) {
        val calls = mutableListOf<String>()
        var reachable = true
    }

    private fun Server.api(): KadansApi = KadansApi.create(
        "http://test",
        InMemoryTokenStore(AuthTokens("a", "r")),
        MockEngine { request ->
            check(reachable) { "offline" }
            val call = "${request.method.value} ${request.url.encodedPath}"
            val body = request.body.toByteArray().decodeToString()
            calls += if (body.isEmpty()) call else "$call $body"
            val (code, json) = answer(call, body)
            respond(json, code, jsonHeaders)
        },
    )

    private class FakeStore(
        override val isSupported: Boolean = true,
        var product: StoreProduct? = StoreProduct("$0.99", 14),
        var owned: List<String> = emptyList(),
        var outcome: PurchaseOutcome = PurchaseOutcome.Cancelled,
    ) : StoreBilling {
        val purchases = mutableListOf<Pair<String, String>>()
        override suspend fun product(productId: String) = product
        override suspend fun purchase(productId: String, accountHash: String): PurchaseOutcome {
            purchases += productId to accountHash
            return outcome
        }
        override suspend fun ownedPurchaseTokens(productId: String) = owned
        override fun manageUrl(productId: String) = "https://play.google.com/store/account/subscriptions?sku=$productId&package=app.kadans"
    }

    private fun gate(server: Server, store: StoreBilling = FakeStore(), phone: Boolean = true) =
        SubscriptionGate(server.api(), store, isPhone = { phone })

    private fun link(token: String) = "POST /billing/google/purchases {\"purchaseToken\":\"$token\"}"

    // ---- the gate before Home ----

    @Test
    fun desktop_is_free_and_a_phone_without_a_store_in_the_app_is_not_locked_out() = runTest(dispatcher) {
        val server = Server { _, _ -> HttpStatusCode.OK to blocked }

        assertFalse(gate(server, phone = false).needsPaywall())
        // The iPhone until its StoreKit step: a paywall there would have no way to pay.
        assertFalse(gate(server, FakeStore(isSupported = false)).needsPaywall())
        assertEquals(emptyList(), server.calls)
    }

    @Test
    fun a_phone_opens_while_paid_up_or_while_subscriptions_are_not_required() = runTest(dispatcher) {
        val store = FakeStore(owned = listOf("t1"))
        var answer = status(required = false)
        val server = Server { _, _ -> HttpStatusCode.OK to answer }

        assertFalse(gate(server, store).needsPaywall())
        answer = paidUp
        assertFalse(gate(server, store).needsPaywall())
        // Nothing to restore while the answer is already yes.
        assertEquals(listOf("GET /billing/subscription", "GET /billing/subscription"), server.calls)
    }

    @Test
    fun the_stores_purchases_are_tried_before_the_paywall() = runTest(dispatcher) {
        val store = FakeStore(owned = listOf("t1"))
        var linked = blocked
        val server = Server { call, _ -> HttpStatusCode.OK to (if (call.startsWith("POST")) linked else blocked) }

        // Expired in the store as well: the paywall.
        assertTrue(gate(server, store).needsPaywall())
        assertEquals(listOf("GET /billing/subscription", link("t1")), server.calls)

        // Bought on another phone with this Google account: in at once.
        linked = paidUp
        assertFalse(gate(server, store).needsPaywall())
    }

    @Test
    fun an_unreachable_server_locks_no_one_out() = runTest(dispatcher) {
        val server = Server { _, _ -> HttpStatusCode.OK to blocked }.apply { reachable = false }

        assertFalse(gate(server).needsPaywall())
    }

    @Test
    fun another_accounts_purchase_is_skipped_and_named_when_nothing_else_helps() = runTest(dispatcher) {
        val store = FakeStore(owned = listOf("theirs", "mine"))
        val server = Server { _, body ->
            if ("theirs" in body) HttpStatusCode.Conflict to otherAccount else HttpStatusCode.OK to (if ("mine" in body) paidUp else blocked)
        }
        val subscriptionGate = gate(server, store)

        assertTrue(subscriptionGate.restore(SubscriptionStatusResponse(required = true, hasAccess = false, googleProductId = "kadans_mobile")).hasAccess)

        store.owned = listOf("theirs")
        val refused = assertFailsWith<KadansApiException> {
            subscriptionGate.restore(SubscriptionStatusResponse(required = true, hasAccess = false, googleProductId = "kadans_mobile"))
        }
        assertEquals("10057", refused.errorCode)
        assertTrue(subscriptionGate.needsPaywall())
    }

    // ---- the paywall ----

    private fun paywall(server: Server, store: FakeStore): PaywallViewModel {
        val api = server.api()
        return PaywallViewModel(api, store, SubscriptionGate(api, store, isPhone = { true }), KadansRealtime(api))
    }

    @Test
    fun the_paywall_sells_the_stores_offer_and_the_server_checks_the_purchase() = runTest(dispatcher) {
        val store = FakeStore(outcome = PurchaseOutcome.Purchased("new-token"))
        val server = Server { call, _ -> HttpStatusCode.OK to (if (call.startsWith("POST")) paidUp else blocked) }
        val viewModel = paywall(server, store)

        val shown = viewModel.state.first { !it.isLoading }
        assertEquals(StoreProduct("$0.99", 14), shown.product)
        assertTrue(shown.canBuy)
        assertFalse(shown.canManage) // nothing bought yet: nothing to manage
        assertFalse(shown.canFake)

        viewModel.subscribe()
        val done = viewModel.state.first { !it.isBusy }
        assertEquals(PaywallExit.Unlocked, done.exit)
        // The purchase names this account, and only the server's check of it counts.
        assertEquals(listOf("kadans_mobile" to "hash-of-alice"), store.purchases)
        assertEquals(link("new-token"), server.calls.last())
    }

    @Test
    fun a_pending_payment_is_linked_and_waits_on_the_paywall() = runTest(dispatcher) {
        val store = FakeStore(outcome = PurchaseOutcome.Pending("cash-token"))
        val server = Server { call, _ -> HttpStatusCode.OK to (if (call.startsWith("POST")) status(state = "Pending", store = "Google") else blocked) }
        val viewModel = paywall(server, store)
        viewModel.state.first { !it.isLoading }

        viewModel.subscribe()
        val waiting = viewModel.state.first { !it.isBusy }
        assertEquals(PaywallNotice.Pending, waiting.notice)
        assertNull(waiting.exit)
        assertTrue(waiting.canManage)
        assertEquals(link("cash-token"), server.calls.last())
    }

    @Test
    fun cancelling_changes_nothing_and_a_store_failure_says_so() = runTest(dispatcher) {
        val store = FakeStore(outcome = PurchaseOutcome.Cancelled)
        val server = Server { _, _ -> HttpStatusCode.OK to blocked }
        val viewModel = paywall(server, store)
        viewModel.state.first { !it.isLoading }

        viewModel.subscribe()
        val cancelled = viewModel.state.first { !it.isBusy }
        assertNull(cancelled.notice)
        assertNull(cancelled.errorCode)

        store.outcome = PurchaseOutcome.Failed("Service unavailable")
        viewModel.subscribe()
        assertEquals(PaywallNotice.StoreFailed, viewModel.state.first { !it.isBusy }.notice)
        assertEquals(listOf("GET /billing/subscription"), server.calls)
    }

    @Test
    fun restoring_links_what_the_store_holds_or_says_nothing_was_found() = runTest(dispatcher) {
        val store = FakeStore()
        val server = Server { call, _ -> HttpStatusCode.OK to (if (call.startsWith("POST")) paidUp else blocked) }
        val viewModel = paywall(server, store)
        viewModel.state.first { !it.isLoading }

        viewModel.restore()
        assertEquals(PaywallNotice.NothingToRestore, viewModel.state.first { !it.isBusy }.notice)

        // Already owned on this Google account: buying again turns into a restore.
        store.owned = listOf("t1")
        store.outcome = PurchaseOutcome.AlreadyOwned
        viewModel.subscribe()
        assertEquals(PaywallExit.Unlocked, viewModel.state.first { !it.isBusy }.exit)
        assertEquals(link("t1"), server.calls.last())
    }

    @Test
    fun a_purchase_for_another_account_shows_the_servers_words() = runTest(dispatcher) {
        val store = FakeStore(outcome = PurchaseOutcome.Purchased("theirs"))
        val server = Server { call, _ -> if (call.startsWith("POST")) HttpStatusCode.Conflict to otherAccount else HttpStatusCode.OK to blocked }
        val viewModel = paywall(server, store)
        viewModel.state.first { !it.isLoading }

        viewModel.subscribe()
        val refused = viewModel.state.first { !it.isBusy }
        assertEquals("10057", refused.errorCode)
        assertEquals("This subscription belongs to another Kadans account.", refused.error)
        assertNull(refused.exit)
    }

    @Test
    fun the_fake_trial_is_there_only_when_the_server_allows_it() = runTest(dispatcher) {
        val server = Server { call, _ -> HttpStatusCode.OK to (if (call.startsWith("POST")) status(hasAccess = true, state = "Trial", store = "Fake") else status(fakeStore = true)) }
        val viewModel = paywall(server, FakeStore(isSupported = false, product = null))

        val shown = viewModel.state.first { !it.isLoading }
        assertNull(shown.product) // no store here: nothing to buy, and the screen says where it is sold
        assertFalse(shown.canBuy)
        assertTrue(shown.canFake)

        viewModel.fakeTrial()
        assertEquals(PaywallExit.Unlocked, viewModel.state.first { !it.isBusy }.exit)
        assertEquals("POST /billing/fake/purchases {\"state\":\"Trial\",\"days\":14}", server.calls.last())
    }

    @Test
    fun a_free_account_goes_straight_to_home() = runTest(dispatcher) {
        val server = Server { _, _ -> HttpStatusCode.OK to status(hasAccess = true).replace("}", ""","freeAccess":true}""") }

        assertFalse(gate(server, FakeStore(owned = listOf("t1"))).needsPaywall())
        assertEquals(listOf("GET /billing/subscription"), server.calls) // nothing to restore
    }

    @Test
    fun a_paywall_opened_once_access_is_back_moves_on() = runTest(dispatcher) {
        val server = Server { _, _ -> HttpStatusCode.OK to paidUp }

        assertEquals(PaywallExit.Unlocked, paywall(server, FakeStore()).state.first { !it.isLoading }.exit)
    }

    // ---- Settings, words, store periods ----

    @Test
    fun settings_says_where_the_subscription_stands() {
        val p = EnglishStrings.paywall
        val end = Instant.parse("2026-10-17T12:00:00Z")
        fun line(state: SubscriptionState?, renewing: Boolean = true) = SubscriptionSettingsViewModel.describe(
            SubscriptionStatusResponse(required = true, state = state, store = BillingStore.Google, expiresAt = end, autoRenewing = renewing), p,
        ) { "Oct 17" }

        assertEquals("Free trial until Oct 17", line(SubscriptionState.Trial))
        assertEquals("Active, renews on Oct 17", line(SubscriptionState.Active))
        assertEquals("Cancelled: ends on Oct 17", line(SubscriptionState.Active, renewing = false))
        assertEquals("Cancelled: ends on Oct 17", line(SubscriptionState.Canceled, renewing = false))
        assertEquals(p.paymentProblem, line(SubscriptionState.OnHold))
        assertEquals(p.paymentProblem, line(SubscriptionState.GracePeriod))
        assertEquals(p.paused, line(SubscriptionState.Paused))
        assertEquals(p.notSubscribed, line(SubscriptionState.Expired))
        assertEquals(p.notSubscribed, line(null))
        assertEquals(p.freeAccess, SubscriptionSettingsViewModel.describe(SubscriptionStatusResponse(required = true, hasAccess = true, freeAccess = true), p) { "" })

        // Left out of Settings until subscriptions are sold, unless this account holds one.
        assertFalse(SubscriptionSettingsViewModel.shows(SubscriptionStatusResponse(required = false)))
        assertTrue(SubscriptionSettingsViewModel.shows(SubscriptionStatusResponse(required = false, store = BillingStore.Fake)))
        assertTrue(SubscriptionSettingsViewModel.shows(SubscriptionStatusResponse(required = true)))
    }

    @Test
    fun the_price_and_trial_read_in_every_language() {
        listOf(EnglishStrings, FrenchStrings, CreoleStrings).forEach { strings ->
            val p = strings.paywall
            val offer = p.trialPrice(14, "$0.99")
            assertTrue("14" in offer && "$0.99" in offer && "{" !in offer, offer)
            assertTrue("$0.99" in p.price("$0.99") && "{" !in p.price("$0.99"))
            listOf(p.trialUntil("x"), p.renewsOn("x"), p.endsOn("x")).forEach { assertTrue("x" in it && "{" !in it, it) }
        }
    }

    @Test
    fun store_periods_count_in_days() {
        assertEquals(14, isoPeriodDays("P14D"))
        assertEquals(14, isoPeriodDays("P2W"))
        assertEquals(30, isoPeriodDays("P1M"))
        assertNull(isoPeriodDays("14 days"))
    }
}
