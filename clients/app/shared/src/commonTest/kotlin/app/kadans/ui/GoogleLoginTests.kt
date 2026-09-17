package app.kadans.ui

import app.kadans.api.AuthTokens
import app.kadans.api.InMemoryTokenStore
import app.kadans.api.KadansApi
import app.kadans.api.model.GoogleProviderResponse
import app.kadans.auth.GoogleCredential
import app.kadans.auth.GoogleSignIn
import app.kadans.auth.GoogleSignInException
import app.kadans.ui.auth.LoginEvent
import app.kadans.ui.auth.LoginViewModel
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
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class GoogleLoginTests {
    private val dispatcher = StandardTestDispatcher()
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val session =
        """{"accessToken":"a","expiresAt":"2027-01-01T13:00:00Z","refreshToken":"r","refreshTokenExpireAt":"2027-01-08T12:00:00Z","mfaRequired":false}"""

    @BeforeTest
    fun before() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun after() = Dispatchers.resetMain()

    private class FakeGoogle(
        override val isSupported: Boolean = true,
        private val outcome: () -> GoogleCredential?,
    ) : GoogleSignIn {
        override fun canSignIn(config: GoogleProviderResponse) = config.desktopClientId != null
        override suspend fun signIn(config: GoogleProviderResponse) = outcome()
    }

    /** Records what each endpoint received, so a test can tell which sign-in route was taken. */
    private class Server(var providers: String, val store: InMemoryTokenStore = InMemoryTokenStore()) {
        val bodies = mutableMapOf<String, String>()
    }

    private fun Server.api(jsonHeaders: io.ktor.http.Headers, session: String): KadansApi = KadansApi.create(
        "http://test",
        store,
        MockEngine { request ->
            val path = request.url.encodedPath
            bodies[path] = request.body.toByteArray().decodeToString()
            respond(if (path.endsWith("auth/providers")) providers else session, HttpStatusCode.OK, jsonHeaders)
        },
    )

    private val configured = """{"google":{"desktopClientId":"desktop-id","webClientId":"web-id"}}"""

    @Test
    fun the_button_follows_what_the_server_says_even_after_the_address_changes() = runTest(dispatcher) {
        val server = Server("""{"google":null}""")
        val viewModel = LoginViewModel(server.api(jsonHeaders, session), FakeGoogle { null })

        // "No" changes nothing in the state, so wait (in real time) for the question to have been asked.
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (server.bodies.isEmpty()) delay(10) } }
        assertEquals(false, viewModel.state.value.googleAvailable)

        server.providers = configured // the user pointed the app at a server that has Google set up
        viewModel.refreshProviders()

        assertTrue(viewModel.state.first { it.googleAvailable }.googleAvailable)
    }

    @Test
    fun an_unsupported_platform_never_asks_the_server() = runTest(dispatcher) {
        val server = Server(configured)
        val viewModel = LoginViewModel(server.api(jsonHeaders, session), FakeGoogle(isSupported = false) { null })

        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, viewModel.state.value.googleAvailable)
        assertTrue(server.bodies.isEmpty())
    }

    @Test
    fun a_desktop_authorization_code_goes_to_the_code_exchange_and_signs_in() = runTest(dispatcher) {
        val server = Server(configured)
        val viewModel = LoginViewModel(
            server.api(jsonHeaders, session),
            FakeGoogle { GoogleCredential.AuthorizationCode("the-code", "the-verifier", "http://127.0.0.1:5000") },
        )
        val event = async { viewModel.events.first() }
        viewModel.state.first { it.googleAvailable }

        viewModel.signInWithGoogle()

        assertIs<LoginEvent.LoggedIn>(event.await())
        assertEquals(AuthTokens("a", "r"), server.store.load())
        val sent = server.bodies.getValue("/auth/external/google/code")
        assertTrue("the-code" in sent && "the-verifier" in sent && "127.0.0.1:5000" in sent, sent)
        assertEquals(false, viewModel.state.value.isGoogleLoading)
    }

    @Test
    fun an_android_id_token_goes_to_the_external_endpoint() = runTest(dispatcher) {
        val server = Server(configured)
        val viewModel = LoginViewModel(server.api(jsonHeaders, session), FakeGoogle { GoogleCredential.IdToken("id.token") })
        val event = async { viewModel.events.first() }
        viewModel.state.first { it.googleAvailable }

        viewModel.signInWithGoogle()

        assertIs<LoginEvent.LoggedIn>(event.await())
        val sent = server.bodies.getValue("/auth/external")
        assertTrue("\"google\"" in sent && "id.token" in sent, sent)
    }

    @Test
    fun backing_out_of_googles_ui_is_silent() = runTest(dispatcher) {
        val server = Server(configured)
        val viewModel = LoginViewModel(server.api(jsonHeaders, session), FakeGoogle { null })
        viewModel.state.first { it.googleAvailable }

        viewModel.signInWithGoogle()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(false, state.isGoogleLoading)
        assertEquals(null, state.errorCode)
        assertEquals(null, server.store.load())
    }

    @Test
    fun a_platform_failure_shows_the_google_error_and_unsticks_the_button() = runTest(dispatcher) {
        val viewModel = LoginViewModel(
            Server(configured).api(jsonHeaders, session),
            FakeGoogle { throw GoogleSignInException("no play services") },
        )
        viewModel.state.first { it.googleAvailable }

        viewModel.signInWithGoogle()

        val state = viewModel.state.first { it.errorCode != null }
        assertEquals("google", state.errorCode)
        assertEquals(false, state.isGoogleLoading)
    }
}
