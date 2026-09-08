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
import app.kadans.api.model.BudgetSettingsResponse
import app.kadans.api.model.CategoryResponse
import app.kadans.api.model.Currency
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
import app.kadans.ui.todos.EndMode
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

data class BudgetAddUiState(
    val accounts: List<AccountResponse> = emptyList(),
    val categories: List<CategoryResponse> = emptyList(),
    val settings: BudgetSettingsResponse? = null,
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
    val endMode: EndMode = EndMode.Never,
    val count: Int? = null,
    val untilDate: LocalDate? = null,
    val byDays: Set<ApiDayOfWeek> = emptySet(),
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

    val endValid: Boolean
        get() = when (endMode) {
            EndMode.Never -> true
            EndMode.AfterCount -> (count ?: 0) > 0
            EndMode.OnDate -> untilDate != null && (date == null || untilDate >= date)
        }

    val canSubmit: Boolean
        get() = !isSaving && account != null && (amount ?: 0.0) > 0.0 &&
            (!repeat || kind == BudgetTransactionKind.Transfer || endValid) &&
            (kind != BudgetTransactionKind.Transfer ||
                (transferAccount != null && (!crossCurrency || (received ?: 0.0) > 0.0)))

    /**
     * Indicative rates pre-fill the received amount by pivoting through the base currency
     * (rate(base) = 1). Only a suggestion — the amount the user actually types IS the exchange.
     */
    fun suggestedReceived(): Double? {
        val value = amount ?: return null
        val from = account?.currency ?: return null
        val to = transferAccount?.currency ?: return null
        if (from == to) return null
        val base = settings?.baseCurrency ?: return null
        fun rateOf(currency: Currency): Double? =
            if (currency == base) 1.0 else settings.rates.firstOrNull { it.currency == currency }?.rateInBase
        val fromRate = rateOf(from) ?: return null
        val toRate = rateOf(to) ?: return null
        if (toRate <= 0.0) return null
        return value * fromRate / toRate
    }
}

class BudgetAddViewModel(private val api: KadansApi) : ViewModel() {
    private val _state = MutableStateFlow(BudgetAddUiState())
    val state: StateFlow<BudgetAddUiState> = _state.asStateFlow()

    private val _saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saved: SharedFlow<Unit> = _saved.asSharedFlow()

    private val timeZone = TimeZone.currentSystemDefault()

    init {
        reload()
    }

    /** Re-runs on every entry of the screen: accounts created since the last open must appear. */
    fun reload() {
        viewModelScope.launch {
            try {
                val accounts = api.budget.accounts()
                val categories = api.budget.categories()
                val settings = runCatching { api.budget.settings() }.getOrNull()
                val today = Clock.System.now().toLocalDateTime(timeZone).date
                val current = _state.value
                _state.value = current.copy(
                    accounts = accounts,
                    categories = categories,
                    settings = settings,
                    accountId = current.accountId?.takeIf { id -> accounts.any { it.id == id } }
                        ?: accounts.firstOrNull()?.id,
                    transferAccountId = current.transferAccountId?.takeIf { id -> accounts.any { it.id == id } },
                    date = current.date ?: today,
                    isLoading = false,
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
                                byDayOfWeek = when {
                                    current.frequency != Frequency.Weekly -> null
                                    current.byDays.isNotEmpty() -> current.byDays.sorted()
                                    else -> current.date?.let { listOf(it.dayOfWeek.toApi()) }
                                },
                                count = current.count.takeIf { current.endMode == EndMode.AfterCount },
                                // Inclusive end of the picked day, so that day's occurrence counts.
                                until = if (current.endMode == EndMode.OnDate)
                                    LocalDateTime(current.untilDate!!, LocalTime(23, 59)).toInstant(timeZone)
                                else null,
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
                // The ViewModel can outlive the screen: unstick the button and present a fresh
                // form next time, keeping the account/category/date the user was working with.
                _state.value = _state.value.copy(
                    isSaving = false,
                    amountText = "",
                    receivedText = "",
                    note = "",
                    repeat = false,
                    endMode = EndMode.Never,
                    count = null,
                    untilDate = null,
                    byDays = emptySet(),
                )
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
