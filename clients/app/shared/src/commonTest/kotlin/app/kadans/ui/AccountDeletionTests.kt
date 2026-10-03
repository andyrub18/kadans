package app.kadans.ui

import app.kadans.api.AuthTokens
import app.kadans.api.InMemoryTokenStore
import app.kadans.api.KadansApi
import app.kadans.api.model.LoginResponse
import app.kadans.api.model.UserResponse
import app.kadans.i18n.CreoleStrings
import app.kadans.i18n.EnglishStrings
import app.kadans.i18n.FrenchStrings
import app.kadans.ui.auth.LoginEvent
import app.kadans.ui.auth.LoginViewModel
import app.kadans.ui.settings.SettingsUiState
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

class AccountDeletionTests {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val erasure = Instant.parse("2026-10-10T14:00:00Z")

    @Test
    fun a_sign_in_into_a_closed_account_offers_to_keep_it() {
        val closed = LoginResponse(deletionScheduled = true, eraseAfter = erasure, restoreToken = "restore")

        assertEquals(LoginEvent.DeletionScheduled(erasure, "restore"), LoginViewModel.outcomeOf(closed))
        assertIs<LoginEvent.MfaRequired>(LoginViewModel.outcomeOf(LoginResponse(mfaRequired = true, mfaToken = "mfa")))
        assertIs<LoginEvent.LoggedIn>(LoginViewModel.outcomeOf(LoginResponse(accessToken = "a", refreshToken = "r")))
    }

    @Test
    fun the_erasure_date_reads_naturally_in_every_language() {
        val day = LocalDate(2026, 10, 9)
        assertEquals("October 9, 2026", EnglishStrings.deletion.date(day))
        assertEquals("9 octobre 2026", FrenchStrings.deletion.date(day))
        assertEquals("9 oktòb 2026", CreoleStrings.deletion.date(day))
        assertEquals(
            "Your account is closed and will be erased on October 9, 2026. Sign in before then to keep it.",
            EnglishStrings.deletion.closedNotice(EnglishStrings.deletion.date(day)),
        )
    }

    @Test
    fun deleting_takes_the_password_unless_the_account_only_uses_google() {
        val user = UserResponse(id = "u1", username = "alice", timeZone = "UTC")
        val state = SettingsUiState(user = user, isLoading = false)

        assertEquals(false, state.canDeleteAccount)
        assertEquals(true, state.copy(deletePassword = "secret").canDeleteAccount)
        assertEquals(true, state.copy(user = user.copy(hasPassword = false)).canDeleteAccount)
        assertEquals(false, state.copy(deletePassword = "secret", isBusy = true).canDeleteAccount)
    }

    @Test
    fun a_closed_account_forgets_its_session_here_and_a_link_does_not() = runTest {
        var answer = """{"eraseAfter":"2026-10-10T14:00:00Z"}"""
        val store = InMemoryTokenStore(AuthTokens("a", "r"))
        val api = KadansApi.create("http://test", store, MockEngine { respond(answer, HttpStatusCode.OK, jsonHeaders) })

        // Without a password: a link went out, nothing is closed yet.
        answer = """{"confirmationSentTo":"alice@example.com"}"""
        assertEquals("alice@example.com", api.account.deleteAccount(null).confirmationSentTo)
        assertEquals(AuthTokens("a", "r"), store.load())

        answer = """{"eraseAfter":"2026-10-10T14:00:00Z"}"""
        assertEquals(erasure, api.account.deleteAccount("secret").eraseAfter)
        assertNull(store.load())
    }

    @Test
    fun deleting_a_todo_is_a_delete_and_keeping_an_account_starts_a_session() = runTest {
        val calls = mutableListOf<String>()
        val store = InMemoryTokenStore()
        val api = KadansApi.create("http://test", store, MockEngine { request ->
            calls += "${request.method.value} ${request.url.encodedPath}"
            val body = if (request.url.encodedPath == "/auth/restore-account")
                """{"accessToken":"fresh","expiresAt":"2027-01-01T13:00:00Z","refreshToken":"ref","refreshTokenExpireAt":"2027-01-08T12:00:00Z"}"""
            else """{}"""
            respond(body, HttpStatusCode.OK, jsonHeaders)
        })

        api.todos.delete("t1")
        api.auth.restoreAccount("restore")

        assertEquals(listOf("${HttpMethod.Delete.value} /todos/t1", "POST /auth/restore-account"), calls)
        assertEquals(AuthTokens("fresh", "ref"), store.load())
    }
}
