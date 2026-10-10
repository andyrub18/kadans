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
import app.kadans.ui.mondayFirstDays
import app.kadans.ui.toApi
import app.kadans.ui.todos.EndMode
import app.kadans.ui.todos.RuleLimits
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
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
    /** Where the server's limits count from: a repeating movement starts at most a year before today. */
    val today: LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault()),
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

    /** Incomes and expenses repeat; transfers are one-off (the server repeats no transfer). */
    val repeating: Boolean get() = repeat && kind != BudgetTransactionKind.Transfer

    /** The days a weekly movement repeats on, as the day picker shows them: the ones picked, else the date's. */
    val weekDays: Set<ApiDayOfWeek> get() = byDays.ifEmpty { setOfNotNull(date?.dayOfWeek?.toApi()) }

    /** The first one: a weekly movement starts on its first chosen day from the date picked, as todos do. */
    val firstDate: LocalDate?
        get() = date?.let { picked ->
            if (!repeating || frequency != Frequency.Weekly) picked
            else (0..6).map { picked.plus(it, DateTimeUnit.DAY) }.first { it.dayOfWeek.toApi() in weekDays }
        }

    /** The server refuses a repeating movement that starts more than a year ago; the start picker offers no earlier day. */
    val earliestStart: LocalDate get() = today.minus(1, DateTimeUnit.YEAR).plus(1, DateTimeUnit.DAY)

    /** A date picked before "Repeat" was turned on can be older: said under the date, and not sent. */
    val startTooOld: Boolean get() = repeating && date != null && date < earliestStart

    /** More repeats than the server accepts: said under the field, and not sent. */
    val countTooHigh: Boolean get() = endMode == EndMode.AfterCount && (count ?: 0) > RuleLimits.MAX_COUNT

    val endValid: Boolean
        get() = when (endMode) {
            EndMode.Never -> true
            EndMode.AfterCount -> (count ?: 0) in 1..RuleLimits.MAX_COUNT
            // The same day or later, and at most ten years after the first one (the end picker offers no other day).
            EndMode.OnDate -> untilDate != null && (firstDate ?: today).let { first ->
                untilDate >= first && untilDate <= RuleLimits.latestEnd(first)
            }
        }

    val canSubmit: Boolean
        get() = !isSaving && account != null && (amount ?: 0.0) > 0.0 &&
            (!repeating || (endValid && !startTooOld)) &&
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
                    today = today,
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
        // Noon local: an unambiguous "that day" instant regardless of DST edges. A repeating one starts on its first day.
        val occurredAt: Instant = (if (current.repeating) current.firstDate else current.date)
            ?.atStartOfDayIn(timeZone)?.plus(12.hours)
            ?: Clock.System.now()
        _state.value = current.copy(isSaving = true, error = null, errorCode = null)
        viewModelScope.launch {
            try {
                if (current.repeating) {
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
                                byDayOfWeek = if (current.frequency == Frequency.Weekly) mondayFirstDays.filter { it in current.weekDays } else null,
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

    internal companion object {
        /** A tap on a day picks or drops it, starting from the days shown; the last one stays. */
        fun toggleDay(state: BudgetAddUiState, day: ApiDayOfWeek): BudgetAddUiState {
            val days = state.weekDays.let { if (day in it) it - day else it + day }
            return if (days.isEmpty()) state else state.copy(byDays = days)
        }
    }
}
