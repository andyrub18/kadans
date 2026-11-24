package app.kadans.ui

import app.kadans.TestMain
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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class GoogleLoginTests {
    private val dispatcher = StandardTestDispatcher()
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val session =
        """{"accessToken":"a","expiresAt":"2027-01-01T13:00:00Z","refreshToken":"r","refreshTokenExpireAt":"2027-01-08T12:00:00Z","mfaRequired":false}"""

    @BeforeTest
    fun before() = TestMain.set(dispatcher)

    @AfterTest
    fun after() = TestMain.reset()

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

        /** How many more times the providers question goes unanswered (no network yet). */
        var unreachable = 0
        var asked = 0
    }

    private fun Server.api(jsonHeaders: io.ktor.http.Headers, session: String): KadansApi = KadansApi.create(
        "http://test",
        store,
        MockEngine { request ->
            val path = request.url.encodedPath
            if (path.endsWith("auth/providers")) {
                asked++
                if (unreachable > 0) {
                    unreachable--
                    error("no network yet")
                }
            }
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
    fun the_button_comes_once_the_server_answers_after_a_start_without_network() = runTest(dispatcher) {
        val server = Server(configured).apply { unreachable = 3 }
        val viewModel = LoginViewModel(server.api(jsonHeaders, session), FakeGoogle { null })

        // The fake server answers on its own thread; the waits between questions run on the test's clock.
        viewModel.state.first { it.googleAvailable }

        assertEquals(4, server.asked)
        assertTrue(testScheduler.currentTime >= 14_000, "asked again after 2 s, 4 s and 8 s: ${testScheduler.currentTime} ms")

        // Answered: never asked again.
        dispatcher.scheduler.advanceTimeBy(300_000)
        withContext(Dispatchers.Default) { delay(200) }
        assertEquals(4, server.asked)
    }

    @Test
    fun a_platform_without_google_asks_only_whether_the_server_is_there() = runTest(dispatcher) {
        val server = Server(configured)
        val viewModel = LoginViewModel(server.api(jsonHeaders, session), FakeGoogle(isSupported = false) { null })

        withContext(Dispatchers.Default) { withTimeout(5_000) { while (server.asked == 0) delay(10) } }
        dispatcher.scheduler.advanceUntilIdle()

        // The server offers Google and this platform cannot use it: no button, and nothing wrong to tell.
        assertEquals(false, viewModel.state.value.googleAvailable)
        assertEquals(false, viewModel.state.value.serverUnreachable)
    }

    @Test
    fun a_server_out_of_reach_is_told_at_once_and_taken_back_when_it_answers() = runTest(dispatcher) {
        val server = Server(configured).apply { unreachable = 2 }
        val viewModel = LoginViewModel(server.api(jsonHeaders, session), FakeGoogle { null })

        // Nothing typed, nothing pressed: the first unanswered question is enough.
        viewModel.state.first { it.serverUnreachable }
        assertEquals(false, viewModel.state.value.googleAvailable)

        // Asked again by itself (2 s, then 4 s): the answer takes the notice back and brings the button.
        val answered = viewModel.state.first { !it.serverUnreachable }
        assertTrue(answered.googleAvailable)
        assertEquals(3, server.asked)
    }

    @Test
    fun retry_asks_again_in_place_of_the_try_that_was_waiting() = runTest(dispatcher) {
        val server = Server(configured).apply { unreachable = 1 }
        val viewModel = LoginViewModel(server.api(jsonHeaders, session), FakeGoogle { null })
        viewModel.state.first { it.serverUnreachable }
        val askedBefore = server.asked

        viewModel.retryServer()
        // Said at once that it is being asked, before any answer.
        assertTrue(viewModel.state.value.isCheckingServer)
        val answered = viewModel.state.first { !it.serverUnreachable }

        assertEquals(false, answered.isCheckingServer)
        assertTrue(answered.googleAvailable)
        // One question more, and no other: the try that was waiting its turn was dropped, not left to ask too.
        dispatcher.scheduler.advanceTimeBy(300_000)
        withContext(Dispatchers.Default) { delay(200) }
        assertEquals(askedBefore + 1, server.asked)
    }

    @Test
    fun a_server_that_refuses_is_there() = runTest(dispatcher) {
        // Busy, or too many requests: an answer. The notice is for a server that gives none.
        var asked = 0
        val api = KadansApi.create(
            "http://test",
            InMemoryTokenStore(),
            MockEngine { _ ->
                asked++
                respond("""{"title":"Server busy","status":503}""", HttpStatusCode.ServiceUnavailable, jsonHeaders)
            },
        )
        val viewModel = LoginViewModel(api, FakeGoogle { null })

        withContext(Dispatchers.Default) { withTimeout(5_000) { while (asked == 0) delay(10) } }
        dispatcher.scheduler.runCurrent()

        assertEquals(false, viewModel.state.value.serverUnreachable)
    }

    @Test
    fun a_sign_in_that_finds_no_server_puts_the_notice_up() = runTest(dispatcher) {
        // The server was there when the screen opened, and is gone when "Sign in" is pressed.
        var down = false
        val api = KadansApi.create(
            "http://test",
            InMemoryTokenStore(),
            MockEngine { request ->
                if (down) error("no network")
                respond(if (request.url.encodedPath.endsWith("auth/providers")) configured else session, HttpStatusCode.OK, jsonHeaders)
            },
        )
        val viewModel = LoginViewModel(api, FakeGoogle { null })
        viewModel.state.first { it.googleAvailable }

        down = true
        viewModel.onUsernameChange("marie")
        viewModel.onPasswordChange("secret")
        viewModel.submit()

        val told = viewModel.state.first { it.serverUnreachable }
        assertEquals("network", told.errorCode)
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
    fun a_sign_in_that_did_not_finish_says_so() = runTest(dispatcher) {
        val server = Server(configured)
        val viewModel = LoginViewModel(server.api(jsonHeaders, session), FakeGoogle { null })
        viewModel.state.first { it.googleAvailable }

        viewModel.signInWithGoogle()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(false, state.isGoogleLoading)
        assertEquals("google", state.errorCode)
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
