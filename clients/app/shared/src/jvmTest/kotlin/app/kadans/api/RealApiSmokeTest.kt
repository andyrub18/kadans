package app.kadans.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Talks to a live backend when KADANS_API_URL is set (dev: http://localhost:5199);
 * silently skipped otherwise so CI needs no server.
 */
class RealApiSmokeTest {
    @Test
    fun full_round_trip_against_the_live_api() {
        val baseUrl = System.getenv("KADANS_API_URL") ?: run {
            println("KADANS_API_URL not set; skipping live API smoke test")
            return
        }
        val user = System.getenv("KADANS_API_USER") ?: "admin"
        val password = System.getenv("KADANS_API_PASSWORD") ?: "Admin123!"

        runBlocking {
            val api = KadansApi.create(baseUrl)

            val login = api.auth.login(user, password)
            assertTrue(login.accessToken != null, "expected a token pair")

            val me = api.account.me()
            assertEquals(user, me.username)

            val todos = api.todos.list(pageSize = 5)
            println("live API: ${todos.size} todo(s), user tz ${me.timeZone}")

            api.notifications.unreadCount()
            val stats = api.pomodoro.stats()
            assertTrue(stats.timeZoneId.isNotBlank())

            val error = runCatching { api.todos.get("00000000-0000-0000-0000-000000000001") }
                .exceptionOrNull() as? KadansApiException
            assertEquals("10019", error?.errorCode, "expected TodoNotFound problem details")

            api.auth.logout()
        }
    }
}
