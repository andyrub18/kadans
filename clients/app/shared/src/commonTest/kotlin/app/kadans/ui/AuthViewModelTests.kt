package app.kadans.ui

import app.kadans.api.AuthTokens
import app.kadans.api.InMemoryTokenStore
import app.kadans.api.KadansApi
import app.kadans.ui.auth.LoginEvent
import app.kadans.ui.auth.LoginViewModel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

class AuthViewModelTests {
    private val dispatcher = StandardTestDispatcher()
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest
    fun before() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun after() = Dispatchers.resetMain()

    private fun api(
        store: InMemoryTokenStore = InMemoryTokenStore(),
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): KadansApi = KadansApi.create("http://test", store, MockEngine { _ -> respond(body, status, jsonHeaders) })

    private fun TestScope.firstEvent(viewModel: LoginViewModel) = async { viewModel.events.first() }

    @Test
    fun successful_login_emits_LoggedIn_and_stores_the_session() = runTest(dispatcher) {
        val store = InMemoryTokenStore()
        val viewModel = LoginViewModel(
            api(store, """{"accessToken":"a","expiresAt":"2027-01-01T13:00:00Z","refreshToken":"r","refreshTokenExpireAt":"2027-01-08T12:00:00Z","mfaRequired":false}""")
        )
        val event = firstEvent(viewModel)

        viewModel.onUsernameChange("alice")
        viewModel.onPasswordChange("pw")
        viewModel.submit()

        assertIs<LoginEvent.LoggedIn>(event.await())
        assertEquals(AuthTokens("a", "r"), store.load())
        assertEquals("", viewModel.state.value.password)
    }

    @Test
    fun mfa_challenge_emits_MfaRequired_with_the_token() = runTest(dispatcher) {
        val viewModel = LoginViewModel(api(body = """{"mfaRequired":true,"mfaToken":"chal-1"}"""))
        val event = firstEvent(viewModel)

        viewModel.onUsernameChange("alice")
        viewModel.onPasswordChange("pw")
        viewModel.submit()

        val mfa = assertIs<LoginEvent.MfaRequired>(event.await())
        assertEquals("chal-1", mfa.mfaToken)
    }

    @Test
    fun invalid_credentials_surface_the_problem_detail() = runTest(dispatcher) {
        val viewModel = LoginViewModel(
            api(
                body = """{"title":"Invalid credentials","status":401,"detail":"Invalid username or password","errorCode":"10024"}""",
                status = HttpStatusCode.Unauthorized,
            )
        )

        viewModel.onUsernameChange("alice")
        viewModel.onPasswordChange("nope")
        viewModel.submit()

        // The mock reply resumes from a real thread; await the state instead of advancing virtual time.
        val state = viewModel.state.first { it.error != null }
        assertEquals(false, state.isLoading)
        assertNotNull(state.error)
        assertEquals("Invalid username or password", state.error)
    }
}
