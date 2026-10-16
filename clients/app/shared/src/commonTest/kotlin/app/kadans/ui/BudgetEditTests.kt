package app.kadans.ui

import app.kadans.api.KadansApi
import app.kadans.api.model.BudgetTransactionKind
import app.kadans.ui.budget.BudgetAddViewModel
import app.kadans.ui.budget.BudgetViewModel
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A movement opened from the month's list to correct it, recurring transfers, and the month's list itself. */
class BudgetEditTests {
    private val dispatcher = StandardTestDispatcher()
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest
    fun before() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun after() = Dispatchers.resetMain()

    private fun account(id: String, name: String, currency: String, archived: Boolean = false) =
        """{"id":"$id","name":"$name","currency":"$currency","type":"Cash","initialBalance":0,"balance":0,""" +
            """"isArchived":$archived,"createdAt":"2026-09-01T00:00:00Z","updatedAt":"2026-09-01T00:00:00Z"}"""

    // Cash in gourdes, a dollar card, and an old dollar wallet closed since.
    private val accounts = "[" + listOf(
        account("a1", "Cash", "Htg"),
        account("a2", "Old wallet", "Usd", archived = true),
        account("a3", "Card", "Usd"),
    ).joinToString(",") + "]"

    private val categories =
        """[{"id":"c1","name":"Food","kind":"Expense"},{"id":"c2","name":"Gone","kind":"Expense","isArchived":true}]"""

    private fun movement(accountId: String = "a1", kind: String = "Expense", extra: String = """"categoryId":"c1",""") =
        """{"id":"t1","accountId":"$accountId","kind":"$kind","amount":250.0,"currency":"Htg",$extra""" +
            """"occurredAt":"2026-09-08T16:23:00Z","note":"market","recurringTransactionId":"r1","createdAt":"2026-09-08T16:23:00Z"}"""

    /** What the app asked, and the body of each write by "METHOD path". */
    private inner class Server(private val edited: String = movement()) {
        val calls = mutableListOf<String>()
        val bodies = mutableMapOf<String, String>()
        val queries = mutableMapOf<String, Map<String, String>>()

        /** Each write, as it reaches the server (the engine answers on its own thread). */
        val writes = Channel<String>(Channel.UNLIMITED)

        val api: KadansApi = KadansApi.create(
            "http://test",
            engine = MockEngine { request ->
                val call = "${request.method.value} ${request.url.encodedPath}"
                calls += call
                queries[call] = request.url.parameters.names().associateWith { request.url.parameters[it]!! }
                (request.body as? TextContent)?.let { bodies[call] = it.text }
                if (request.method.value != "GET") writes.trySend(call)
                val body = when {
                    call == "GET /budget/transactions/t1" -> edited
                    call == "PUT /budget/transactions/t1" -> edited
                    call == "DELETE /budget/transactions/t1" -> "{}"
                    call.startsWith("GET /budget/transactions") -> "[]"
                    call.startsWith("GET /budget/accounts") -> accounts
                    call.startsWith("GET /budget/categories") -> categories
                    call.startsWith("GET /budget/settings") -> """{"baseCurrency":"Htg","rates":[]}"""
                    call.startsWith("GET /budget/summary") ->
                        """{"year":2026,"month":10,"timeZoneId":"America/Port-au-Prince","totals":[],"accounts":[],"categories":[]}"""
                    call == "GET /budget/recurring/" -> "[$exchangeRule]"
                    call == "POST /budget/recurring/" || call == "PUT /budget/recurring/r9" -> exchangeRule
                    else -> "{}"
                }
                respond(body, HttpStatusCode.OK, jsonHeaders)
            },
        )

        fun sent(call: String): JsonObject = Json.parseToJsonElement(bodies.getValue(call)).jsonObject
    }

    private val exchangeRule =
        """{"id":"r9","accountId":"a1","kind":"Transfer","amount":1320.0,"currency":"Htg","note":"",""" +
            """"transferAccountId":"a3","transferAmount":10.0,"rrule":"FREQ=MONTHLY","timeZoneId":"America/Port-au-Prince",""" +
            """"startDate":"2026-10-01T16:00:00Z","isActive":true,"createdAt":"2026-10-01T00:00:00Z"}"""

    @Test
    fun an_expense_put_on_the_wrong_account_moves_and_keeps_its_time() = runTest(dispatcher) {
        val server = Server()
        val viewModel = BudgetAddViewModel(server.api, "t1")
        val form = viewModel.state.first { !it.isLoading }

        assertTrue(form.editing)
        assertTrue(form.bookedByRule) // a recurring one booked it: the rule stays as it is
        assertEquals(BudgetTransactionKind.Expense, form.kind)
        assertEquals("250.00", form.amountText)
        assertEquals("c1", form.categoryId)
        assertEquals("market", form.note)
        // Archived accounts and categories are not offered, unless the movement is on them.
        assertEquals(listOf("a1", "a3"), form.accounts.map { it.id })
        assertEquals(listOf("c1"), form.categories.map { it.id })

        val saved = async { viewModel.saved.first() }
        viewModel.update { it.copy(accountId = "a3", amountText = "12.5") }
        viewModel.submit()
        saved.await()

        val sent = server.sent("PUT /budget/transactions/t1")
        assertEquals("a3", sent["accountId"]!!.jsonPrimitive.content)
        assertEquals(12.5, sent["amount"]!!.jsonPrimitive.content.toDouble())
        assertEquals("c1", sent["categoryId"]!!.jsonPrimitive.content)
        // Left on its day, it keeps its exact time; an expense has no destination.
        assertEquals(Instant.parse("2026-09-08T16:23:00Z"), Instant.parse(sent["occurredAt"]!!.jsonPrimitive.content))
        assertNull(sent["transferAccountId"])
    }

    @Test
    fun a_new_day_is_sent_at_its_noon() = runTest(dispatcher) {
        val server = Server()
        val viewModel = BudgetAddViewModel(server.api, "t1")
        viewModel.state.first { !it.isLoading }

        val saved = async { viewModel.saved.first() }
        viewModel.update { it.copy(date = LocalDate(2026, 9, 10)) }
        viewModel.submit()
        saved.await()

        val noon = LocalDate(2026, 9, 10).atStartOfDayIn(TimeZone.currentSystemDefault()) + 12.hours
        assertEquals(noon, Instant.parse(server.sent("PUT /budget/transactions/t1")["occurredAt"]!!.jsonPrimitive.content))
    }

    @Test
    fun an_old_movement_of_an_archived_account_stays_on_it() = runTest(dispatcher) {
        val server = Server(movement(accountId = "a2"))
        val viewModel = BudgetAddViewModel(server.api, "t1")
        val form = viewModel.state.first { !it.isLoading }

        // Not moved to the first account by default: fixing its note leaves it where it is.
        assertEquals("a2", form.accountId)
        assertEquals(listOf("a1", "a2", "a3"), form.accounts.map { it.id })
    }

    @Test
    fun a_transfer_into_dollars_opens_with_its_received_amount() = runTest(dispatcher) {
        val server = Server(movement(kind = "Transfer", extra = """"transferAccountId":"a3","transferAmount":1.9,"""))
        val viewModel = BudgetAddViewModel(server.api, "t1")
        val form = viewModel.state.first { !it.isLoading }

        assertEquals("a3", form.transferAccountId)
        assertEquals("1.90", form.receivedText)
        assertTrue(form.crossCurrency)
        assertFalse(form.repeating) // an edited movement is one movement

        val saved = async { viewModel.saved.first() }
        viewModel.submit()
        saved.await()
        val sent = server.sent("PUT /budget/transactions/t1")
        assertEquals("a3", sent["transferAccountId"]!!.jsonPrimitive.content)
        assertEquals(1.9, sent["transferAmount"]!!.jsonPrimitive.content.toDouble())
        assertNull(sent["categoryId"])
    }

    @Test
    fun deleting_it_goes_back_to_the_budget() = runTest(dispatcher) {
        val server = Server()
        val viewModel = BudgetAddViewModel(server.api, "t1")
        viewModel.state.first { !it.isLoading }

        val saved = async { viewModel.saved.first() }
        viewModel.delete()
        saved.await()
        assertTrue("DELETE /budget/transactions/t1" in server.calls)
    }

    @Test
    fun a_transfer_repeats_with_its_destination_and_received_amount() = runTest(dispatcher) {
        val server = Server()
        val viewModel = BudgetAddViewModel(server.api)
        viewModel.state.first { !it.isLoading }

        val saved = async { viewModel.saved.first() }
        viewModel.update {
            it.copy(
                kind = BudgetTransactionKind.Transfer, accountId = "a1", transferAccountId = "a3",
                amountText = "1320", receivedText = "10", repeat = true,
            )
        }
        assertTrue(viewModel.state.value.canSubmit)
        viewModel.submit()
        saved.await()

        val sent = server.sent("POST /budget/recurring/")
        assertEquals("Transfer", sent["kind"]!!.jsonPrimitive.content)
        assertEquals("a3", sent["transferAccountId"]!!.jsonPrimitive.content)
        assertEquals(10.0, sent["transferAmount"]!!.jsonPrimitive.content.toDouble())
        assertNull(sent["categoryId"])
    }

    @Test
    fun the_months_movements_are_those_of_the_month_shown_in_its_time_zone() = runTest(dispatcher) {
        // Port-au-Prince is on summer time until the first Sunday of November: October starts at 04:00 UTC.
        assertEquals(
            Instant.parse("2026-10-01T04:00:00Z") to Instant.parse("2026-11-01T04:00:00Z"),
            BudgetViewModel.monthWindow(2026, 10, "America/Port-au-Prince"),
        )
        assertEquals(
            Instant.parse("2026-12-01T05:00:00Z") to Instant.parse("2027-01-01T05:00:00Z"),
            BudgetViewModel.monthWindow(2026, 12, "America/Port-au-Prince"),
        )

        val server = Server()
        val viewModel = BudgetViewModel(server.api)
        val shown = viewModel.state.first { !it.isLoading }
        val (from, to) = BudgetViewModel.monthWindow(shown.year, shown.month, "America/Port-au-Prince")
        val asked = server.queries.getValue("GET /budget/transactions/")
        assertEquals(from, Instant.parse(asked.getValue("from")))
        assertEquals(to, Instant.parse(asked.getValue("to")))
        // Archived accounts too: an old movement still names its account.
        assertEquals("true", server.queries.getValue("GET /budget/accounts/")["includeArchived"])
    }

    @Test
    fun a_recurring_exchange_paused_or_changed_sends_its_received_amount() = runTest(dispatcher) {
        val server = Server()
        val viewModel = BudgetViewModel(server.api)
        val rule = viewModel.state.first { !it.isLoading && it.recurring.isNotEmpty() }.recurring.single()

        viewModel.toggleRecurring(rule)
        assertEquals("PUT /budget/recurring/r9", server.writes.receive())
        val paused = server.sent("PUT /budget/recurring/r9")
        assertEquals("false", paused["isActive"]!!.jsonPrimitive.content)
        assertEquals(10.0, paused["transferAmount"]!!.jsonPrimitive.content.toDouble())

        viewModel.updateRecurring(rule, amount = 1400.0, received = 10.5, categoryId = null, note = " savings ")
        assertEquals("PUT /budget/recurring/r9", server.writes.receive())
        val changed = server.sent("PUT /budget/recurring/r9")
        assertEquals(1400.0, changed["amount"]!!.jsonPrimitive.content.toDouble())
        assertEquals(10.5, changed["transferAmount"]!!.jsonPrimitive.content.toDouble())
        assertEquals("savings", changed["note"]!!.jsonPrimitive.content)
    }
}
