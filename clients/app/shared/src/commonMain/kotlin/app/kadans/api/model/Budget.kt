@file:UseSerializers(IsoInstantSerializer::class)

package app.kadans.api.model

import app.kadans.api.IsoInstantSerializer
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
enum class Currency { Htg, Usd }

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

@Serializable
data class SetCategoryBudget(val monthlyLimit: Double, val currency: Currency)

@Serializable
data class CategoryBudgetResponse(val categoryId: String, val monthlyLimit: Double, val currency: Currency)

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

@Serializable
data class UpdateBudgetTransaction(
    val amount: Double,
    val occurredAt: Instant,
    val categoryId: String? = null,
    val note: String = "",
    val transferAmount: Double? = null,
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
)

@Serializable
data class CreateRecurringTransaction(
    val accountId: String,
    val kind: BudgetTransactionKind,
    val amount: Double,
    val recurrence: BudgetRecurrence,
    val categoryId: String? = null,
    val note: String = "",
)

@Serializable
data class UpdateRecurringTransaction(
    val amount: Double,
    val categoryId: String? = null,
    val note: String = "",
    val isActive: Boolean = true,
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
    val rrule: String,
    val timeZoneId: String,
    val startDate: Instant,
    val nextAt: Instant? = null,
    val isActive: Boolean = true,
    val createdAt: Instant,
)

@Serializable
data class SetExchangeRate(val htgPerUsd: Double)

@Serializable
data class ExchangeRateResponse(val htgPerUsd: Double? = null, val updatedAt: Instant? = null)

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
    val monthlyLimit: Double? = null,
)

@Serializable
data class CombinedAtYourRate(
    val htgPerUsd: Double,
    val income: Double,
    val expense: Double,
    val net: Double,
    val totalBalance: Double,
)

@Serializable
data class MonthlySummaryResponse(
    val year: Int,
    val month: Int,
    val timeZoneId: String,
    val totals: List<CurrencyTotals>,
    val accounts: List<AccountResponse>,
    val categories: List<CategorySpend>,
    val combined: CombinedAtYourRate? = null,
)
