package app.kadans.ui

import app.kadans.api.KadansApi
import app.kadans.api.model.AccountResponse
import app.kadans.api.model.AccountType
import app.kadans.api.model.ApiDayOfWeek
import app.kadans.api.model.BudgetTransactionKind
import app.kadans.api.model.Currency
import app.kadans.api.model.Frequency
import app.kadans.ui.budget.BudgetAddUiState
import app.kadans.ui.budget.BudgetAddViewModel
import app.kadans.ui.budget.amountInput
import app.kadans.ui.todos.EndMode
import app.kadans.ui.todos.RuleLimits
import io.ktor.http.content.TextContent
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

class BudgetAddViewModelTests {
    private val dispatcher = StandardTestDispatcher()
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest
    fun before() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun after() = Dispatchers.resetMain()

    private val accountJson =
        """{"id":"a1","name":"Cash","currency":"Htg","type":"Cash","initialBalance":0,"balance":0,""" +
            """"isArchived":false,"createdAt":"2026-09-01T00:00:00Z","updatedAt":"2026-09-01T00:00:00Z"}"""

    private fun api(): KadansApi = KadansApi.create(
        "http://test",
        engine = MockEngine { request ->
            val path = request.url.encodedPath
            val body = when {
                path.contains("accounts") -> "[$accountJson]"
                path.contains("categories") -> "[]"
                path.contains("settings") -> """{"baseCurrency":"Htg","rates":[]}"""
                path.contains("transactions") ->
                    """{"id":"t1","accountId":"a1","kind":"Expense","amount":250.0,"currency":"Htg",""" +
                        """"occurredAt":"2026-09-08T16:00:00Z","note":"","createdAt":"2026-09-08T16:00:00Z"}"""
                else -> "{}"
            }
            respond(body, HttpStatusCode.OK, jsonHeaders)
        },
    )

    /**
     * The regression that shipped: the ViewModel outlives the screen, and a successful save left
     * isSaving=true — reopening the movement screen showed a forever-spinning button.
     */
    @Test
    fun successful_save_unsticks_the_button_and_resets_the_form() = runTest(dispatcher) {
        val viewModel = BudgetAddViewModel(api())
        val saved = async { viewModel.saved.first() }

        viewModel.state.first { !it.isLoading } // the account list has loaded
        viewModel.update { it.copy(amountText = "250", note = "market") }
        viewModel.submit()
        saved.await()

        val state = viewModel.state.value
        assertEquals(false, state.isSaving)
        assertEquals("", state.amountText)
        assertEquals("", state.note)
        assertEquals("a1", state.accountId) // the selection survives for the next movement
    }

    // ---- What the server accepts: the form keeps within it ----

    private val today = LocalDate(2026, 10, 9) // a Friday
    private val cash = AccountResponse(
        id = "a1", name = "Cash", currency = Currency.Htg, type = AccountType.Cash, initialBalance = 0.0, balance = 0.0,
        createdAt = Instant.parse("2026-09-01T00:00:00Z"), updatedAt = Instant.parse("2026-09-01T00:00:00Z"),
    )

    private fun salary(frequency: Frequency = Frequency.Monthly) = BudgetAddUiState(
        accounts = listOf(cash), accountId = "a1", kind = BudgetTransactionKind.Income, amountText = "25000",
        date = today, today = today, repeat = true, frequency = frequency, isLoading = false,
    )

    @Test
    fun a_repeat_count_is_capped_at_the_servers_limit() {
        val byCount = salary().copy(endMode = EndMode.AfterCount)

        assertTrue(byCount.copy(count = 5_000).canSubmit)
        assertTrue(byCount.copy(count = 5_001).countTooHigh)
        assertFalse(byCount.copy(count = 5_001).canSubmit)
        assertFalse(byCount.copy(count = 0).canSubmit)
        assertEquals(1234, RuleLimits.countInput("1 2-34"))
    }

    @Test
    fun the_end_is_within_ten_years_of_the_first_one() {
        val byDate = salary().copy(endMode = EndMode.OnDate)

        assertTrue(byDate.copy(untilDate = LocalDate(2036, 10, 8)).canSubmit)
        assertFalse(byDate.copy(untilDate = LocalDate(2036, 10, 9)).canSubmit) // 23:59 then is past ten years from noon
        assertFalse(byDate.copy(untilDate = LocalDate(2026, 10, 8)).canSubmit)
    }

    @Test
    fun a_repeating_movement_starts_at_most_a_year_ago() {
        assertEquals(LocalDate(2025, 10, 10), salary().earliestStart)
        assertTrue(salary().copy(date = LocalDate(2025, 10, 9)).startTooOld)
        assertFalse(salary().copy(date = LocalDate(2025, 10, 9)).canSubmit)
        assertTrue(salary().copy(date = LocalDate(2025, 10, 10)).canSubmit)
        // A one-off movement can be on any day, and a transfer never repeats.
        assertTrue(salary().copy(repeat = false, date = LocalDate(2020, 1, 1)).canSubmit)
        assertFalse(salary().copy(kind = BudgetTransactionKind.Transfer, date = LocalDate(2020, 1, 1)).startTooOld)
    }

    @Test
    fun an_amount_keeps_two_decimals_and_twelve_digits() {
        assertEquals("12.34", amountInput("12.345"))
        assertEquals("1,5", amountInput("1,5"))
        assertEquals("1000", amountInput("1 000"))
        assertEquals("1.23", amountInput("1.2.3"))
        assertEquals("5", amountInput("-5"))
        assertEquals("", amountInput("abc"))
        assertEquals("999999999999.99", amountInput("9999999999999.999"))
    }

    @Test
    fun weekly_days_start_from_the_dates_own_day() {
        val saturday = salary(Frequency.Weekly).copy(date = LocalDate(2026, 10, 10))
        assertEquals(setOf(ApiDayOfWeek.Saturday), saturday.weekDays)

        val withMonday = BudgetAddViewModel.toggleDay(saturday, ApiDayOfWeek.Monday)
        val mondayOnly = BudgetAddViewModel.toggleDay(withMonday, ApiDayOfWeek.Saturday)
        assertEquals(setOf(ApiDayOfWeek.Monday), mondayOnly.weekDays)
        assertEquals(LocalDate(2026, 10, 12), mondayOnly.firstDate)
        assertEquals(mondayOnly, BudgetAddViewModel.toggleDay(mondayOnly, ApiDayOfWeek.Monday)) // the last day stays
    }

    @Test
    fun a_weekly_movement_is_sent_starting_on_its_first_chosen_day() = runTest(dispatcher) {
        var sent: String? = null
        val api = KadansApi.create(
            "http://test",
            engine = MockEngine { request ->
                val path = request.url.encodedPath
                if (path.contains("recurring")) sent = (request.body as TextContent).text
                val body = when {
                    path.contains("recurring") -> "{}"
                    path.contains("accounts") -> "[$accountJson]"
                    path.contains("categories") -> "[]"
                    path.contains("settings") -> """{"baseCurrency":"Htg","rates":[]}"""
                    else -> "{}"
                }
                respond(body, HttpStatusCode.OK, jsonHeaders)
            },
        )
        val viewModel = BudgetAddViewModel(api)
        viewModel.state.first { !it.isLoading }
        viewModel.update {
            it.copy(
                kind = BudgetTransactionKind.Income, amountText = "1500", repeat = true, frequency = Frequency.Weekly,
                date = LocalDate(2026, 10, 10), byDays = setOf(ApiDayOfWeek.Thursday, ApiDayOfWeek.Monday),
            )
        }
        viewModel.submit()
        viewModel.state.first { !it.isSaving } // the engine answers on its own thread

        val recurrence = Json.parseToJsonElement(sent!!).jsonObject["recurrence"]!!.jsonObject
        val zone = TimeZone.currentSystemDefault()
        val monday = LocalDate(2026, 10, 12).atStartOfDayIn(zone) + 12.hours
        assertEquals(monday, Instant.parse(recurrence["startDate"]!!.jsonPrimitive.content))
        assertEquals(listOf("Monday", "Thursday"), recurrence["byDayOfWeek"]!!.jsonArray.map { it.jsonPrimitive.content })
    }
}