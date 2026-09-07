package app.kadans.ui

import app.kadans.api.model.AccountResponse
import app.kadans.api.model.AccountType
import app.kadans.api.model.BudgetTransactionKind
import app.kadans.api.model.Currency
import app.kadans.ui.budget.BudgetAddUiState
import app.kadans.ui.budget.formatAmount
import app.kadans.ui.budget.formatMoney
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class BudgetLogicTests {
    private val t0 = Instant.parse("2026-09-01T12:00:00Z")

    private fun account(id: String, currency: Currency) =
        AccountResponse(id, id, currency, AccountType.Cash, 0.0, 0.0, false, t0, t0)

    @Test
    fun amountsFormatWithSpaceGroupsAndTwoDecimals() {
        assertEquals("85 000.00", formatAmount(85_000.0))
        assertEquals("2 500.50", formatAmount(2500.5))
        assertEquals("0.29", formatAmount(0.29))
        assertEquals("-13 200.00", formatAmount(-13_200.0))
        assertEquals("1 234 567.89 HTG", formatMoney(1_234_567.89, Currency.Htg))
    }

    @Test
    fun crossCurrencyTransfersPrefillFromTheUsersRate() {
        val state = BudgetAddUiState(
            accounts = listOf(account("htg", Currency.Htg), account("usd", Currency.Usd)),
            htgPerUsd = 132.0,
            kind = BudgetTransactionKind.Transfer,
            accountId = "htg",
            transferAccountId = "usd",
            amountText = "13200",
        )
        assertEquals(true, state.crossCurrency)
        assertEquals(100.0, state.suggestedReceived())

        // and the other direction multiplies
        val reverse = state.copy(accountId = "usd", transferAccountId = "htg", amountText = "100")
        assertEquals(13_200.0, reverse.suggestedReceived())

        // same currency: nothing to suggest, nothing extra to enter
        val same = state.copy(transferAccountId = "htg", accountId = "htg")
        assertEquals(false, same.crossCurrency)
    }

    @Test
    fun noRateMeansNoSuggestionButTransferStillPossible() {
        val state = BudgetAddUiState(
            accounts = listOf(account("htg", Currency.Htg), account("usd", Currency.Usd)),
            htgPerUsd = null,
            kind = BudgetTransactionKind.Transfer,
            accountId = "htg",
            transferAccountId = "usd",
            amountText = "13200",
            receivedText = "100",
        )
        assertNull(state.suggestedReceived())
        assertEquals(true, state.canSubmit)
        // without the received amount a cross-currency transfer cannot submit
        assertEquals(false, state.copy(receivedText = "").canSubmit)
    }
}
