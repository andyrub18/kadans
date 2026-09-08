package app.kadans.ui

import app.kadans.api.KadansApi
import app.kadans.ui.budget.BudgetAddViewModel
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
}
