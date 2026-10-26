package app.kadans.ui

import app.kadans.api.KadansApi
import app.kadans.api.model.Currency
import app.kadans.api.model.LimitPeriod
import app.kadans.ui.budget.BudgetViewModel
import app.kadans.sync.DataChanges
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A category's limit: what the screen reads of it, and what setting or removing one sends. */
class BudgetLimitTests {
    private val dispatcher = StandardTestDispatcher()
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest
    fun before() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun after() = Dispatchers.resetMain()

    private val summary =
        """{"year":2026,"month":10,"timeZoneId":"America/Port-au-Prince","totals":[],"accounts":[],""" +
            """"categories":[{"categoryId":"c1","name":"Food","kind":"Expense","currency":"Htg","amount":10000},""" +
            """{"categoryId":"c1","name":"Food","kind":"Expense","currency":"Usd","amount":20}],""" +
            """"limits":[{"categoryId":"c1","amount":15000,"currency":"Htg","period":"Week",""" +
            """"periodStart":"2026-10-12T04:00:00Z","periodEnd":"2026-10-19T04:00:00Z","spent":12640,"percent":84,""" +
            """"converted":["Usd"],"missingRates":["Eur"]}]}"""

    private inner class Server {
        val writes = Channel<Pair<String, String?>>(Channel.UNLIMITED)
        val api: KadansApi = KadansApi.create(
            "http://test",
            engine = MockEngine { request ->
                val call = "${request.method.value} ${request.url.encodedPath}"
                if (request.method.value != "GET") writes.trySend(call to (request.body as? TextContent)?.text)
                val body = when {
                    call.startsWith("GET /budget/summary") -> summary
                    call == "PUT /budget/categories/c1/budget" -> """{"categoryId":"c1","amount":12000,"currency":"Htg","period":"Month"}"""
                    call.startsWith("GET /budget/settings") -> """{"baseCurrency":"Htg","rates":[]}"""
                    call.startsWith("GET /budget/categories") -> """[{"id":"c1","name":"Food","kind":"Expense"}]"""
                    call.startsWith("GET /budget/recurring") || call.startsWith("GET /budget/transactions") ||
                        call.startsWith("GET /budget/accounts") -> "[]"
                    else -> "{}"
                }
                respond(body, HttpStatusCode.OK, jsonHeaders)
            },
        )
    }

    @Test
    fun the_screen_reads_a_limit_in_its_period_with_its_estimate() = runTest(dispatcher) {
        val viewModel = BudgetViewModel(Server().api, DataChanges(emptyFlow()))
        val limit = viewModel.state.first { it.summary != null }.summary!!.limits.single()

        assertEquals(LimitPeriod.Week, limit.period)
        assertEquals(84, limit.percent)
        assertEquals(12640.0, limit.spent)
        assertEquals(listOf(Currency.Usd), limit.converted)
        assertEquals(listOf(Currency.Eur), limit.missingRates)
    }

    @Test
    fun setting_and_removing_a_limit_send_its_currency_and_period() = runTest(dispatcher) {
        val server = Server()
        val viewModel = BudgetViewModel(server.api, DataChanges(emptyFlow()))
        viewModel.state.first { it.summary != null }

        viewModel.setCategoryLimit("c1", 12000.0, Currency.Htg, LimitPeriod.Month)
        val (call, body) = server.writes.receive()
        assertEquals("PUT /budget/categories/c1/budget", call)
        val sent = Json.parseToJsonElement(body!!).jsonObject
        assertEquals(12000.0, sent["amount"]!!.jsonPrimitive.content.toDouble())
        assertEquals("Htg", sent["currency"]!!.jsonPrimitive.content)
        assertEquals("Month", sent["period"]!!.jsonPrimitive.content)

        viewModel.removeCategoryLimit("c1")
        assertEquals("DELETE /budget/categories/c1/budget", server.writes.receive().first)
    }
}
