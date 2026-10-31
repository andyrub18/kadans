package app.kadans.updates

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The desktop's request for latest.json: through the site's redirect to GitHub, with nothing of the person on it (a
 * client of its own, never the API's: no token).
 */
class DesktopUpdateCheckTests {
    private val body = """{"version":"1.1.0","notes":{"en":"Faster reminders."}}"""
    private val github = "https://github.com/andyrub18/kadans/releases/latest/download/latest.json"

    @Test
    fun it_follows_the_site_to_github_and_sends_nothing_of_the_person() = runTest {
        val seen = mutableListOf<Pair<String, Boolean>>()
        val client = HttpClient(MockEngine { request ->
            seen += request.url.toString() to (HttpHeaders.Authorization in request.headers || HttpHeaders.Cookie in request.headers)
            when (request.url.host) {
                "kadansplanning.com" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, github))
                else -> respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }) { expectSuccess = false }

        assertEquals(body, DesktopUpdateCheck(client).fetch())
        assertEquals(listOf(AppUpdates.LATEST_URL to false, github to false), seen)
    }

    @Test
    fun not_found_an_error_or_no_network_is_no_answer() = runTest {
        for (engine in listOf(
            MockEngine { respondError(HttpStatusCode.NotFound) },
            MockEngine { respondError(HttpStatusCode.InternalServerError) },
            MockEngine { error("no network") },
        )) {
            assertNull(DesktopUpdateCheck(HttpClient(engine) { expectSuccess = false }).fetch())
        }
    }

    @Test
    fun a_body_too_big_to_be_the_file_is_no_answer() = runTest {
        val huge = "x".repeat(AppUpdates.MAX_BODY + 1)
        val client = HttpClient(MockEngine { respond(huge, HttpStatusCode.OK) }) { expectSuccess = false }

        assertNull(DesktopUpdateCheck(client).fetch())
        assertTrue(huge.length > AppUpdates.MAX_BODY)
    }
}
