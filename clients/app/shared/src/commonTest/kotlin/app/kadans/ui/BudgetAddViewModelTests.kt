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
import app.kadans.i18n.EnglishStrings
import app.kadans.ui.todos.CreateTodoUiState
import app.kadans.ui.todos.CreateTodoViewModel
import app.kadans.ui.todos.DayKind
import app.kadans.ui.todos.DayRule
import app.kadans.ui.todos.EndMode
import app.kadans.ui.todos.Ordinal
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
        date = today, today = today, repeat = true, isLoading = false,
    ).ruled { it.copy(frequency = frequency) }

    /** The rule changed as the builder changes it: from what it shows, on the movement's date. */
    private fun BudgetAddUiState.ruled(transform: (CreateTodoUiState) -> CreateTodoUiState) = copy(repeatForm = transform(rule))

    @Test
    fun a_repeat_count_is_capped_at_the_servers_limit() {
        val byCount = salary().ruled { it.copy(endMode = EndMode.AfterCount) }

        assertTrue(byCount.ruled { it.copy(count = 5_000) }.canSubmit)
        assertTrue(byCount.ruled { it.copy(count = 5_001) }.rule.countTooHigh)
        assertFalse(byCount.ruled { it.copy(count = 5_001) }.canSubmit)
        assertFalse(byCount.ruled { it.copy(count = 0) }.canSubmit)
        assertEquals(1234, RuleLimits.countInput("1 2-34"))
    }

    @Test
    fun the_end_is_within_ten_years_of_the_first_one() {
        val byDate = salary().ruled { it.copy(endMode = EndMode.OnDate) }

        assertTrue(byDate.ruled { it.copy(untilDate = LocalDate(2036, 10, 8)) }.canSubmit)
        assertFalse(byDate.ruled { it.copy(untilDate = LocalDate(2036, 10, 9)) }.canSubmit) // 23:59 then is past ten years from noon
        assertFalse(byDate.ruled { it.copy(untilDate = LocalDate(2026, 10, 8)) }.canSubmit)
    }

    @Test
    fun a_repeating_movement_starts_at_most_a_year_ago() {
        assertEquals(LocalDate(2025, 10, 10), salary().earliestStart)
        assertTrue(salary().copy(date = LocalDate(2025, 10, 9)).startTooOld)
        assertFalse(salary().copy(date = LocalDate(2025, 10, 9)).canSubmit)
        assertTrue(salary().copy(date = LocalDate(2025, 10, 10)).canSubmit)
        // A one-off movement can be on any day; a repeating transfer starts within the year too.
        assertTrue(salary().copy(repeat = false, date = LocalDate(2020, 1, 1)).canSubmit)
        assertTrue(salary().copy(kind = BudgetTransactionKind.Transfer, date = LocalDate(2020, 1, 1)).startTooOld)
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
        assertEquals(setOf(ApiDayOfWeek.Saturday), saturday.rule.weekDays)

        val withMonday = saturday.ruled { CreateTodoViewModel.toggleDay(it, ApiDayOfWeek.Monday) }
        val mondayOnly = withMonday.ruled { CreateTodoViewModel.toggleDay(it, ApiDayOfWeek.Saturday) }
        assertEquals(setOf(ApiDayOfWeek.Monday), mondayOnly.rule.weekDays)
        assertEquals(LocalDate(2026, 10, 12), mondayOnly.firstDate)
    }

    @Test
    fun a_salary_on_the_15th_and_the_30th_is_the_rule_a_todo_would_send() {
        // Picked on Friday 9 October: the 15th and the 30th, as the todo form's month grid sends them.
        val twice = salary().ruled { CreateTodoViewModel.toggleMonthDay(CreateTodoViewModel.toggleMonthDay(it, 15), 30) }
            .ruled { CreateTodoViewModel.toggleMonthDay(it, 9) }
        val zone = TimeZone.of("America/Port-au-Prince")
        val recurrence = BudgetAddViewModel.recurrence(twice, zone)!!

        assertEquals(Frequency.Monthly, recurrence.frequency)
        assertEquals(listOf(15, 30), recurrence.byMonthDay)
        assertEquals(LocalDate(2026, 10, 15), twice.firstDate) // the first one is the 15th, at noon there
        assertEquals(LocalDate(2026, 10, 15).atStartOfDayIn(zone) + 12.hours, recurrence.startDate)
        assertEquals("Every month · on days 15, 30", BudgetAddViewModel.ruleInWords(twice, EnglishStrings, zone))
        // February has no 30th: said under the days, with the last day as the way to every month.
        assertEquals(30, twice.rule.skippedMonthDay)
        assertEquals(null, twice.ruled { CreateTodoViewModel.toggleMonthDay(CreateTodoViewModel.toggleMonthDay(it, 30), -1) }.rule.skippedMonthDay)
    }

    @Test
    fun rent_on_the_last_friday_of_march_and_june() {
        val rent = salary().ruled {
            it.copy(frequency = Frequency.Yearly, months = setOf(3, 6), dayRule = DayRule.ByWeekday, ordinal = Ordinal.Last, dayKind = DayKind.Friday)
        }
        val recurrence = BudgetAddViewModel.recurrence(rent, TimeZone.UTC)!!

        // "The last Friday" of each of several months is a monthly rule kept to them, as for todos.
        assertEquals(Frequency.Monthly, recurrence.frequency)
        assertEquals(listOf(3, 6), recurrence.byMonth)
        assertEquals(listOf(ApiDayOfWeek.Friday), recurrence.byDayOfWeek)
        assertEquals(listOf(-1), recurrence.bySetPos)
        assertEquals(LocalDate(2027, 3, 26), rent.firstDate)
        assertEquals("Every year · March, June · the last Friday", BudgetAddViewModel.ruleInWords(rent, EnglishStrings, TimeZone.UTC))
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
                kind = BudgetTransactionKind.Income, amountText = "1500", repeat = true, date = LocalDate(2026, 10, 10),
            ).ruled { r -> r.copy(frequency = Frequency.Weekly, byDays = setOf(ApiDayOfWeek.Thursday, ApiDayOfWeek.Monday)) }
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