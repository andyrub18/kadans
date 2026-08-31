package app.kadans.api

import app.kadans.api.model.OccurrenceStatus
import app.kadans.api.model.TodoOccurrenceResponse
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class KadansApiTests {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun loginJson(access: String, refresh: String) =
        """{"accessToken":"$access","expiresAt":"2027-01-01T13:00:00+00:00","refreshToken":"$refresh","refreshTokenExpireAt":"2027-01-08T12:00:00+00:00","mfaRequired":false,"mfaToken":null}"""

    private val userJson =
        """{"id":"u1","username":"alice","email":"a@example.com","emailConfirmed":true,"displayName":"Alice","timeZone":"America/Port-au-Prince","twoFactorEnabled":false,"isActive":true,"roles":["Admin"]}"""

    @Test
    fun login_saves_the_session_and_me_sends_the_bearer() = runTest {
        var seenAuthorization: String? = null
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/auth/login" -> respond(loginJson("acc-1", "ref-1"), HttpStatusCode.OK, jsonHeaders)
                "/users/me" -> {
                    seenAuthorization = request.headers[HttpHeaders.Authorization]
                    respond(userJson, HttpStatusCode.OK, jsonHeaders)
                }
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val store = InMemoryTokenStore()
        val api = KadansApi.create("http://test", store, engine)

        val login = api.auth.login("alice", "pw")
        val me = api.account.me()

        assertEquals("acc-1", login.accessToken)
        assertEquals(AuthTokens("acc-1", "ref-1"), store.load())
        assertEquals("Bearer acc-1", seenAuthorization)
        assertEquals("alice", me.username)
        assertEquals(Instant.parse("2027-01-01T13:00:00Z"), login.expiresAt)
    }

    @Test
    fun mfa_challenge_saves_nothing() = runTest {
        val engine = MockEngine { _ ->
            respond("""{"mfaRequired":true,"mfaToken":"challenge"}""", HttpStatusCode.OK, jsonHeaders)
        }
        val store = InMemoryTokenStore()
        val api = KadansApi.create("http://test", store, engine)

        val login = api.auth.login("alice", "pw")

        assertTrue(login.mfaRequired)
        assertEquals("challenge", login.mfaToken)
        assertNull(store.load())
    }

    @Test
    fun expired_access_token_is_refreshed_and_the_call_retried() = runTest {
        var refreshBody: String? = null
        var meCalls = 0
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/users/me" -> {
                    meCalls++
                    if (request.headers[HttpHeaders.Authorization] == "Bearer fresh-acc")
                        respond(userJson, HttpStatusCode.OK, jsonHeaders)
                    else
                        respond("""{"title":"Unauthorized","status":401}""", HttpStatusCode.Unauthorized, jsonHeaders)
                }
                "/auth/refresh" -> {
                    refreshBody = (request.body as TextContent).text
                    respond(loginJson("fresh-acc", "fresh-ref"), HttpStatusCode.OK, jsonHeaders)
                }
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val store = InMemoryTokenStore(AuthTokens("stale-acc", "old-ref"))
        val api = KadansApi.create("http://test", store, engine)

        val me = api.account.me()

        assertEquals("alice", me.username)
        assertEquals(2, meCalls)
        assertTrue(refreshBody!!.contains("old-ref"))
        assertEquals(AuthTokens("fresh-acc", "fresh-ref"), store.load())
    }

    @Test
    fun failed_refresh_clears_the_session() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/auth/refresh" ->
                    respond("""{"title":"Invalid credentials","status":401,"errorCode":"10024"}""", HttpStatusCode.Unauthorized, jsonHeaders)
                else ->
                    respond("""{"title":"Unauthorized","status":401}""", HttpStatusCode.Unauthorized, jsonHeaders)
            }
        }
        val store = InMemoryTokenStore(AuthTokens("stale", "revoked"))
        val api = KadansApi.create("http://test", store, engine)

        assertFailsWith<KadansApiException> { api.account.me() }
        assertNull(store.load())
    }

    @Test
    fun problem_details_become_a_typed_exception() = runTest {
        val engine = MockEngine { _ ->
            respond(
                """{"type":"","title":"Invalid or expired token","status":400,"detail":"The reset link is invalid or expired.","instance":"/auth/reset-password","errorCode":"10033"}""",
                HttpStatusCode.BadRequest,
                jsonHeaders,
            )
        }
        val api = KadansApi.create("http://test", InMemoryTokenStore(), engine)

        val error = assertFailsWith<KadansApiException> {
            api.auth.resetPassword(app.kadans.api.model.ResetPasswordRequest("a@b.c", "tok", "New123!"))
        }

        assertEquals(400, error.httpStatus)
        assertEquals("10033", error.errorCode)
        assertEquals("The reset link is invalid or expired.", error.message)
    }

    @Test
    fun occurrence_previews_decode_with_offset_instants() {
        val json = """{"id":null,"todoId":"t1","todoTitle":"Standup","scheduledAt":"2027-01-04T14:00:00+00:00","originalScheduledAt":"2027-01-04T14:00:00+00:00","status":"Pending","isRescheduled":false,"rescheduleReason":null,"completedAt":null,"cancelledAt":null,"cancellationReason":null,"remarks":null,"isPreview":true}"""

        val occurrence = KadansJson.decodeFromString(TodoOccurrenceResponse.serializer(), json)

        assertNull(occurrence.id)
        assertTrue(occurrence.isPreview)
        assertEquals(OccurrenceStatus.Pending, occurrence.status)
        assertEquals(Instant.parse("2027-01-04T14:00:00Z"), occurrence.scheduledAt)
    }
}
