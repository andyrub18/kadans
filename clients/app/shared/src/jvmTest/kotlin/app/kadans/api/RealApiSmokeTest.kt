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

            // budget round trip: the typed client against the real routes
            val account = api.budget.createAccount(
                app.kadans.api.model.CreateAccount("smoke client acct", app.kadans.api.model.Currency.Htg)
            )
            api.budget.createTransaction(
                app.kadans.api.model.CreateBudgetTransaction(
                    account.id, app.kadans.api.model.BudgetTransactionKind.Income, 1234.56,
                    kotlin.time.Clock.System.now(),
                )
            )
            val refreshed = api.budget.accounts().first { it.id == account.id }
            assertEquals(1234.56, refreshed.balance, "income should land in the balance")
            val summary = api.budget.summary(2026, 9)
            assertTrue(summary.timeZoneId.isNotBlank())
            api.budget.setRate(app.kadans.api.model.Currency.Usd, 132.5)
            val settings = api.budget.settings()
            assertEquals(132.5, settings.rates.first { it.currency == app.kadans.api.model.Currency.Usd }.rateInBase)
            api.budget.updateAccount(
                account.id,
                app.kadans.api.model.UpdateAccount(account.name, account.type, isArchived = true),
            )

            api.auth.logout()
        }
    }
}
