package app.kadans.ui

import app.kadans.api.model.AccountResponse
import app.kadans.api.model.AccountType
import app.kadans.api.model.BudgetTransactionKind
import app.kadans.api.model.BudgetSettingsResponse
import app.kadans.api.model.Currency
import app.kadans.api.model.CurrencyRateResponse
import app.kadans.ui.todos.EndMode
import app.kadans.ui.budget.BudgetAddUiState
import app.kadans.ui.budget.formatAmount
import app.kadans.ui.budget.formatMoney
import kotlin.test.Test
import kotlinx.datetime.LocalDate
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
            settings = BudgetSettingsResponse(
                baseCurrency = Currency.Htg,
                rates = listOf(CurrencyRateResponse(Currency.Usd, 132.0, Instant.parse("2026-09-07T00:00:00Z"))),
            ),
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
            settings = BudgetSettingsResponse(baseCurrency = Currency.Htg),
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

    @Test
    fun repeatEndModesGateSubmission() {
        val base = BudgetAddUiState(
            accounts = listOf(account("htg", Currency.Htg)),
            kind = BudgetTransactionKind.Expense,
            accountId = "htg",
            amountText = "250",
            date = LocalDate(2026, 9, 8),
            repeat = true,
        )
        // Never: fine as-is. After-count needs a count; on-date needs a day at/after the start.
        assertEquals(true, base.canSubmit)
        assertEquals(false, base.copy(endMode = EndMode.AfterCount).canSubmit)
        assertEquals(true, base.copy(endMode = EndMode.AfterCount, count = 30).canSubmit)
        assertEquals(false, base.copy(endMode = EndMode.OnDate).canSubmit)
        assertEquals(false, base.copy(endMode = EndMode.OnDate, untilDate = LocalDate(2026, 9, 1)).canSubmit)
        assertEquals(true, base.copy(endMode = EndMode.OnDate, untilDate = LocalDate(2026, 12, 31)).canSubmit)
    }
}
