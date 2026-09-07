package app.kadans.ui.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.api.model.AccountResponse
import app.kadans.api.model.ApiDayOfWeek
import app.kadans.api.model.BudgetRecurrence
import app.kadans.api.model.BudgetTransactionKind
import app.kadans.api.model.CategoryKind
import app.kadans.api.model.CategoryResponse
import app.kadans.api.model.CreateBudgetTransaction
import app.kadans.api.model.CreateRecurringTransaction
import app.kadans.api.model.Frequency
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

data class BudgetAddUiState(
    val accounts: List<AccountResponse> = emptyList(),
    val categories: List<CategoryResponse> = emptyList(),
    val htgPerUsd: Double? = null,
    val kind: BudgetTransactionKind = BudgetTransactionKind.Expense,
    val accountId: String? = null,
    val transferAccountId: String? = null,
    val amountText: String = "",
    val receivedText: String = "",
    val categoryId: String? = null,
    val note: String = "",
    val date: LocalDate? = null,
    // repeat
    val repeat: Boolean = false,
    val frequency: Frequency = Frequency.Monthly,
    val interval: Int = 1,
    val count: Int? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
    val errorCode: String? = null,
) {
    val account: AccountResponse? get() = accounts.firstOrNull { it.id == accountId }
    val transferAccount: AccountResponse? get() = accounts.firstOrNull { it.id == transferAccountId }
    val amount: Double? get() = amountText.replace(" ", "").replace(",", ".").toDoubleOrNull()
    val received: Double? get() = receivedText.replace(" ", "").replace(",", ".").toDoubleOrNull()
    val crossCurrency: Boolean
        get() = kind == BudgetTransactionKind.Transfer &&
            account != null && transferAccount != null &&
            account!!.currency != transferAccount!!.currency

    val matchingCategories: List<CategoryResponse>
        get() = when (kind) {
            BudgetTransactionKind.Income -> categories.filter { it.kind == CategoryKind.Income }
            BudgetTransactionKind.Expense -> categories.filter { it.kind == CategoryKind.Expense }
            BudgetTransactionKind.Transfer -> emptyList()
        }

    val canSubmit: Boolean
        get() = !isSaving && account != null && (amount ?: 0.0) > 0.0 &&
            (kind != BudgetTransactionKind.Transfer ||
                (transferAccount != null && (!crossCurrency || (received ?: 0.0) > 0.0)))

    /** The rate pre-fills the received amount for cross-currency transfers. */
    fun suggestedReceived(): Double? {
        val rate = htgPerUsd ?: return null
        val value = amount ?: return null
        val from = account?.currency ?: return null
        val to = transferAccount?.currency ?: return null
        return when {
            from == to -> null
            to.name == "Usd" -> value / rate
            else -> value * rate
        }
    }
}

class BudgetAddViewModel(private val api: KadansApi) : ViewModel() {
    private val _state = MutableStateFlow(BudgetAddUiState())
    val state: StateFlow<BudgetAddUiState> = _state.asStateFlow()

    private val _saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saved: SharedFlow<Unit> = _saved.asSharedFlow()

    private val timeZone = TimeZone.currentSystemDefault()

    init {
        viewModelScope.launch {
            try {
                val accounts = api.budget.accounts()
                val categories = api.budget.categories()
                val rate = runCatching { api.budget.exchangeRate().htgPerUsd }.getOrNull()
                val today = Clock.System.now().toLocalDateTime(timeZone).date
                _state.value = _state.value.copy(
                    accounts = accounts, categories = categories, htgPerUsd = rate,
                    accountId = accounts.firstOrNull()?.id, date = today, isLoading = false,
                )
            } catch (e: KadansApiException) {
                _state.value = _state.value.copy(isLoading = false, error = e.message, errorCode = e.errorCode)
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, errorCode = "network")
            }
        }
    }

    fun update(transform: (BudgetAddUiState) -> BudgetAddUiState) {
        _state.value = transform(_state.value)
    }

    fun submit() {
        val current = _state.value
        val account = current.account ?: return
        val amount = current.amount ?: return
        // Noon local: an unambiguous "that day" instant regardless of DST edges.
        val occurredAt: Instant = current.date?.atStartOfDayIn(timeZone)?.plus(12.hours)
            ?: Clock.System.now()
        _state.value = current.copy(isSaving = true, error = null, errorCode = null)
        viewModelScope.launch {
            try {
                if (current.repeat && current.kind != BudgetTransactionKind.Transfer) {
                    api.budget.createRecurring(
                        CreateRecurringTransaction(
                            accountId = account.id,
                            kind = current.kind,
                            amount = amount,
                            recurrence = BudgetRecurrence(
                                frequency = current.frequency,
                                startDate = occurredAt,
                                interval = current.interval,
                                byMonthDay = if (current.frequency == Frequency.Monthly)
                                    current.date?.let { listOf(it.day) } else null,
                                byDayOfWeek = if (current.frequency == Frequency.Weekly)
                                    current.date?.let { listOf(it.dayOfWeek.toApi()) } else null,
                                count = current.count,
                                timeZone = timeZone.id,
                            ),
                            categoryId = current.categoryId,
                            note = current.note.trim(),
                        ),
                    )
                } else {
                    api.budget.createTransaction(
                        CreateBudgetTransaction(
                            accountId = account.id,
                            kind = current.kind,
                            amount = amount,
                            occurredAt = occurredAt,
                            categoryId = if (current.kind == BudgetTransactionKind.Transfer) null else current.categoryId,
                            note = current.note.trim(),
                            transferAccountId = current.transferAccountId
                                .takeIf { current.kind == BudgetTransactionKind.Transfer },
                            transferAmount = current.received.takeIf { current.crossCurrency },
                        ),
                    )
                }
                _saved.emit(Unit)
            } catch (e: KadansApiException) {
                _state.value = _state.value.copy(isSaving = false, error = e.message, errorCode = e.errorCode)
            } catch (e: Exception) {
                _state.value = _state.value.copy(isSaving = false, errorCode = "network")
            }
        }
    }
}

private fun DayOfWeek.toApi(): ApiDayOfWeek = when (this) {
    DayOfWeek.SUNDAY -> ApiDayOfWeek.Sunday
    DayOfWeek.MONDAY -> ApiDayOfWeek.Monday
    DayOfWeek.TUESDAY -> ApiDayOfWeek.Tuesday
    DayOfWeek.WEDNESDAY -> ApiDayOfWeek.Wednesday
    DayOfWeek.THURSDAY -> ApiDayOfWeek.Thursday
    DayOfWeek.FRIDAY -> ApiDayOfWeek.Friday
    DayOfWeek.SATURDAY -> ApiDayOfWeek.Saturday
}
