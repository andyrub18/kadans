@file:UseSerializers(IsoInstantSerializer::class)

package app.kadans.api.model

import app.kadans.api.IsoInstantSerializer
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
enum class Currency { Htg, Usd, Eur, Cad, Dop, Mxn }

@Serializable
enum class AccountType { Cash, Bank, MobileMoney, Card, Savings, Other }

@Serializable
enum class CategoryKind { Income, Expense }

@Serializable
enum class BudgetTransactionKind { Income, Expense, Transfer }

@Serializable
data class CreateAccount(
    val name: String,
    val currency: Currency,
    val type: AccountType = AccountType.Cash,
    val initialBalance: Double = 0.0,
)

@Serializable
data class UpdateAccount(val name: String, val type: AccountType, val isArchived: Boolean = false)

@Serializable
data class AccountResponse(
    val id: String,
    val name: String,
    val currency: Currency,
    val type: AccountType,
    val initialBalance: Double,
    val balance: Double,
    val isArchived: Boolean = false,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Serializable
data class CreateCategory(val name: String, val kind: CategoryKind, val icon: String? = null)

@Serializable
data class UpdateCategory(val name: String, val icon: String? = null, val isArchived: Boolean = false)

@Serializable
data class CategoryResponse(
    val id: String,
    val name: String,
    val kind: CategoryKind,
    val icon: String? = null,
    val isArchived: Boolean = false,
)

/** How often a category's limit starts again: weeks on Monday, years on 1 January, in the person's time zone. */
@Serializable
enum class LimitPeriod { Week, Month, Year }

/** A category's limit set, or its amount and period changed: its currency is the one it was set in. */
@Serializable
data class SetCategoryLimit(val amount: Double, val currency: Currency, val period: LimitPeriod = LimitPeriod.Month)

@Serializable
data class CategoryLimitResponse(val categoryId: String, val amount: Double, val currency: Currency, val period: LimitPeriod)

@Serializable
data class CreateBudgetTransaction(
    val accountId: String,
    val kind: BudgetTransactionKind,
    val amount: Double,
    val occurredAt: Instant,
    val categoryId: String? = null,
    val note: String = "",
    val transferAccountId: String? = null,
    val transferAmount: Double? = null,
)

/** A movement corrected; [accountId] and [transferAccountId] move it (left out, it stays). Its kind never changes. */
@Serializable
data class UpdateBudgetTransaction(
    val amount: Double,
    val occurredAt: Instant,
    val categoryId: String? = null,
    val note: String = "",
    val transferAmount: Double? = null,
    val accountId: String? = null,
    val transferAccountId: String? = null,
)

@Serializable
data class BudgetTransactionResponse(
    val id: String,
    val accountId: String,
    val kind: BudgetTransactionKind,
    val amount: Double,
    val currency: Currency,
    val occurredAt: Instant,
    val categoryId: String? = null,
    val note: String = "",
    val transferAccountId: String? = null,
    val transferAmount: Double? = null,
    val recurringTransactionId: String? = null,
    val createdAt: Instant,
)

/** A budget rule's dates, with a todo rule's day parts: the form is the todo's rule builder (`ui/RepeatSection`). */
@Serializable
data class BudgetRecurrence(
    val frequency: Frequency,
    val startDate: Instant,
    val interval: Int = 1,
    val byDayOfWeek: List<ApiDayOfWeek>? = null,
    val byMonthDay: List<Int>? = null,
    val count: Int? = null,
    val until: Instant? = null,
    val timeZone: String? = null,
    val bySetPos: List<Int>? = null,
    val byMonth: List<Int>? = null,
)

@Serializable
data class CreateRecurringTransaction(
    val accountId: String,
    val kind: BudgetTransactionKind,
    val amount: Double,
    val recurrence: BudgetRecurrence,
    val categoryId: String? = null,
    val note: String = "",
    val transferAccountId: String? = null,
    /** Between two currencies: what the destination receives each time. */
    val transferAmount: Double? = null,
)

/** What the next bookings carry, or a pause; a transfer between two currencies sends its received amount. */
@Serializable
data class UpdateRecurringTransaction(
    val amount: Double,
    val categoryId: String? = null,
    val note: String = "",
    val isActive: Boolean = true,
    val transferAmount: Double? = null,
)

@Serializable
data class RecurringTransactionResponse(
    val id: String,
    val accountId: String,
    val kind: BudgetTransactionKind,
    val amount: Double,
    val currency: Currency,
    val categoryId: String? = null,
    val note: String = "",
    val transferAccountId: String? = null,
    val transferAmount: Double? = null,
    val rrule: String,
    val timeZoneId: String,
    val startDate: Instant,
    val frequency: Frequency = Frequency.Monthly,
    val interval: Int = 1,
    val count: Int? = null,
    val until: Instant? = null,
    val nextAt: Instant? = null,
    val isActive: Boolean = true,
    val createdAt: Instant,
)

@Serializable
data class SetBaseCurrency(val baseCurrency: Currency)

@Serializable
data class SetCurrencyRate(val rateInBase: Double)

@Serializable
data class CurrencyRateResponse(val currency: Currency, val rateInBase: Double, val updatedAt: Instant)

@Serializable
data class BudgetSettingsResponse(
    val baseCurrency: Currency = Currency.Htg,
    val rates: List<CurrencyRateResponse> = emptyList(),
)

@Serializable
data class CurrencyTotals(val currency: Currency, val income: Double, val expense: Double, val net: Double)

@Serializable
data class CategorySpend(
    val categoryId: String,
    val name: String,
    val kind: CategoryKind,
    val icon: String? = null,
    val currency: Currency,
    val amount: Double,
)

/**
 * A category's limit in the period the month shown covers (that month, the year through it, or this week): [spent] in
 * its currency, other currencies at the person's rates ([converted]), those without a rate left out ([missingRates]).
 */
@Serializable
data class CategoryLimitStatus(
    val categoryId: String,
    val amount: Double,
    val currency: Currency,
    val period: LimitPeriod,
    val periodStart: Instant,
    val periodEnd: Instant,
    val spent: Double,
    val percent: Int,
    val converted: List<Currency> = emptyList(),
    val missingRates: List<Currency> = emptyList(),
)

/** Today's estimate in the base currency; currencies without a rate are listed, never guessed. */
@Serializable
data class CombinedEstimate(
    val baseCurrency: Currency,
    val income: Double,
    val expense: Double,
    val net: Double,
    val totalBalance: Double,
    val missingRates: List<Currency> = emptyList(),
)

@Serializable
data class MonthlySummaryResponse(
    val year: Int,
    val month: Int,
    val timeZoneId: String,
    val totals: List<CurrencyTotals>,
    val accounts: List<AccountResponse>,
    val categories: List<CategorySpend>,
    val combined: CombinedEstimate? = null,
    val limits: List<CategoryLimitStatus> = emptyList(),
)
