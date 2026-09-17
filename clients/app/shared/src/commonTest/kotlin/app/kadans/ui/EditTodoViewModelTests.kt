package app.kadans.ui

import app.kadans.api.KadansApi
import app.kadans.ui.todos.EditTodoViewModel
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

class EditTodoViewModelTests {
    private val dispatcher = StandardTestDispatcher()
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest
    fun before() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun after() = Dispatchers.resetMain()

    private val todoJson =
        """{"id":"t1","title":"Water the plants","description":"","status":"Scheduled","notificationEnabled":true,""" +
            """"notifyBeforeInMinutes":15,"createdAt":"2026-09-01T00:00:00Z","updatedAt":"2026-09-01T00:00:00Z"}"""

    private fun api(): KadansApi = KadansApi.create(
        "http://test",
        engine = MockEngine { respond(todoJson, HttpStatusCode.OK, jsonHeaders) },
    )

    /**
     * Same regression class as the budget movement screen: a successful save must leave the
     * ViewModel ready for another save, whether or not the platform hands the screen the same
     * instance again.
     */
    @Test
    fun successful_save_unsticks_the_button() = runTest(dispatcher) {
        val viewModel = EditTodoViewModel(api(), "t1")
        val saved = async { viewModel.saved.first() }

        viewModel.state.first { !it.isLoading } // the todo has loaded
        viewModel.update { it.copy(title = "Water the plants, twice") }
        viewModel.save()
        saved.await()

        val state = viewModel.state.value
        assertEquals(false, state.isSaving)
        assertEquals(true, state.canSave)
    }
}
