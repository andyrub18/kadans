package app.kadans.ui.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.api.model.AccountResponse
import app.kadans.api.model.BudgetRecurrence
import app.kadans.api.model.BudgetTransactionKind
import app.kadans.api.model.CategoryKind
import app.kadans.api.model.BudgetSettingsResponse
import app.kadans.api.model.CategoryResponse
import app.kadans.api.model.Currency
import app.kadans.api.model.CreateBudgetTransaction
import app.kadans.api.model.CreateRecurringTransaction
import app.kadans.api.model.Frequency
import app.kadans.api.model.UpdateBudgetTransaction
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
import app.kadans.i18n.StringsCatalog
import app.kadans.ui.todos.CreateTodoUiState
import app.kadans.ui.todos.CreateTodoViewModel
import app.kadans.ui.todos.END_OF_DAY
import app.kadans.ui.todos.EndMode
import app.kadans.ui.todos.RuleSummary
import app.kadans.ui.todos.TodoMode
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

/** A new rule: monthly, on the movement's day, at noon (money moves on a day, not at a time). */
private val NEW_RULE = CreateTodoUiState(mode = TodoMode.Recurring, frequency = Frequency.Monthly, time = LocalTime(12, 0))

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
    /**
     * How a repeating movement repeats: a todo rule's form state, edited by the same builder ([RepeatSection]), so money
     * repeats as todos do ("the 15th and the 30th", "the last Friday"). Its title and reminder go unused; its day is
     * the movement's [date] (see [rule]).
     */
    val repeatForm: CreateTodoUiState = NEW_RULE,
    /** Where the server's limits count from: a repeating movement starts at most a year before today. */
    val today: LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault()),
    /** The movement being corrected (opened from the month's list); null for a new one. */
    val editingId: String? = null,
    /** When the edited movement happened, and its day: saved on the same day, it keeps its exact time. */
    val originalOccurredAt: Instant? = null,
    val originalDate: LocalDate? = null,
    /** The edited movement was booked by a recurring one, which stays as it is. */
    val bookedByRule: Boolean = false,
    val isLoading: Boolean = true,
    /** The accounts (or the movement to edit) could not be loaded: the form has nothing to offer. */
    val loadFailed: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    val errorCode: String? = null,
) {
    val editing: Boolean get() = editingId != null

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

    /** Any new movement repeats, a transfer too ("100 to savings every month"); an edited one is a single movement. */
    val repeating: Boolean get() = repeat && !editing

    /** The rule as the builder shows and sends it: on the movement's date, unless other days are picked. */
    val rule: CreateTodoUiState get() = repeatForm.copy(date = date)

    /** The first one: a repeating movement starts on the first day from the date picked its rule falls on, as todos do. */
    val firstDate: LocalDate? get() = if (repeating) rule.firstDate else date

    /** The server refuses a repeating movement that starts more than a year ago; the start picker offers no earlier day. */
    val earliestStart: LocalDate get() = today.minus(1, DateTimeUnit.YEAR).plus(1, DateTimeUnit.DAY)

    /** A date picked before "Repeat" was turned on can be older: said under the date, and not sent. */
    val startTooOld: Boolean get() = repeating && date != null && date < earliestStart

    /** The rule can go: an end the server takes, a day it falls on, and a start within the year. */
    val ruleValid: Boolean
        get() = rule.let { it.endValid && !it.neverFalls && !it.severalMonthsNeedEveryYear } && !startTooOld

    val canSubmit: Boolean
        get() = !isSaving && account != null && (amount ?: 0.0) > 0.0 &&
            (!repeating || ruleValid) &&
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

/** A new movement, or with [transactionId] one to correct (its kind stays; a recurring one is changed on the budget screen). */
class BudgetAddViewModel(private val api: KadansApi, private val transactionId: String? = null) : ViewModel() {
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
        if (_state.value.loadFailed) {
            _state.value = _state.value.copy(isLoading = true, loadFailed = false, error = null, errorCode = null)
        }
        viewModelScope.launch {
            try {
                val edited = transactionId?.let { api.budget.transaction(it) }
                // An edited movement keeps its own accounts and category, archived since or not; nothing else archived shows.
                val accounts = api.budget.accounts(includeArchived = edited != null)
                    .filter { !it.isArchived || it.id == edited?.accountId || it.id == edited?.transferAccountId }
                val categories = api.budget.categories(includeArchived = edited != null)
                    .filter { !it.isArchived || it.id == edited?.categoryId }
                val settings = runCatching { api.budget.settings() }.getOrNull()
                val today = Clock.System.now().toLocalDateTime(timeZone).date
                val current = _state.value
                val loaded = current.copy(
                    accounts = accounts,
                    categories = categories,
                    settings = settings,
                    today = today,
                    isLoading = false,
                    loadFailed = false,
                    error = null,
                    errorCode = null,
                )
                // The form is filled from the movement once: coming back to the screen keeps what was changed.
                _state.value = if (edited != null && !current.editing) {
                    val day = edited.occurredAt.toLocalDateTime(timeZone).date
                    val destination = accounts.firstOrNull { it.id == edited.transferAccountId }
                    loaded.copy(
                        editingId = edited.id,
                        kind = edited.kind,
                        accountId = edited.accountId,
                        transferAccountId = edited.transferAccountId,
                        amountText = editableAmount(edited.amount),
                        receivedText = edited.transferAmount
                            ?.takeIf { destination != null && destination.currency != edited.currency }
                            ?.let(::editableAmount) ?: "",
                        categoryId = edited.categoryId,
                        note = edited.note,
                        date = day,
                        originalDate = day,
                        originalOccurredAt = edited.occurredAt,
                        bookedByRule = edited.recurringTransactionId != null,
                    )
                } else {
                    loaded.copy(
                        accountId = current.accountId?.takeIf { id -> accounts.any { it.id == id } }
                            ?: accounts.firstOrNull()?.id,
                        transferAccountId = current.transferAccountId?.takeIf { id -> accounts.any { it.id == id } },
                        date = current.date ?: today,
                    )
                }
            } catch (e: KadansApiException) {
                _state.value = _state.value.copy(isLoading = false, loadFailed = true, error = e.message, errorCode = e.errorCode)
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, loadFailed = true, errorCode = "network")
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
        // Noon local: an unambiguous "that day" instant regardless of DST edges. A repeating one starts on its first day;
        // an edited one left on its day keeps its time.
        val occurredAt: Instant = current.originalOccurredAt?.takeIf { current.date == current.originalDate }
            ?: (if (current.repeating) current.firstDate else current.date)
                ?.atStartOfDayIn(timeZone)?.plus(12.hours)
            ?: Clock.System.now()
        val transfer = current.kind == BudgetTransactionKind.Transfer
        _state.value = current.copy(isSaving = true, error = null, errorCode = null)
        viewModelScope.launch {
            try {
                if (current.editingId != null) {
                    api.budget.updateTransaction(
                        current.editingId,
                        UpdateBudgetTransaction(
                            amount = amount,
                            occurredAt = occurredAt,
                            categoryId = current.categoryId.takeIf { !transfer },
                            note = current.note.trim(),
                            transferAmount = current.received.takeIf { current.crossCurrency },
                            accountId = account.id,
                            transferAccountId = current.transferAccountId.takeIf { transfer },
                        ),
                    )
                    _state.value = _state.value.copy(isSaving = false)
                    _saved.emit(Unit)
                    return@launch
                }
                if (current.repeating) {
                    api.budget.createRecurring(
                        CreateRecurringTransaction(
                            accountId = account.id,
                            kind = current.kind,
                            amount = amount,
                            recurrence = recurrence(current, timeZone)!!,
                            categoryId = current.categoryId.takeIf { !transfer },
                            note = current.note.trim(),
                            transferAccountId = current.transferAccountId.takeIf { transfer },
                            transferAmount = current.received.takeIf { current.crossCurrency },
                        ),
                    )
                } else {
                    api.budget.createTransaction(
                        CreateBudgetTransaction(
                            accountId = account.id,
                            kind = current.kind,
                            amount = amount,
                            occurredAt = occurredAt,
                            categoryId = current.categoryId.takeIf { !transfer },
                            note = current.note.trim(),
                            transferAccountId = current.transferAccountId.takeIf { transfer },
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
                    repeatForm = NEW_RULE,
                )
                _saved.emit(Unit)
            } catch (e: KadansApiException) {
                _state.value = _state.value.copy(isSaving = false, error = e.message, errorCode = e.errorCode)
            } catch (e: Exception) {
                _state.value = _state.value.copy(isSaving = false, errorCode = "network")
            }
        }
    }

    /** The edited movement goes; the budget screen is shown again. */
    fun delete() {
        val id = _state.value.editingId ?: return
        _state.value = _state.value.copy(isSaving = true, error = null, errorCode = null)
        viewModelScope.launch {
            try {
                api.budget.deleteTransaction(id)
                _state.value = _state.value.copy(isSaving = false)
                _saved.emit(Unit)
            } catch (e: KadansApiException) {
                _state.value = _state.value.copy(isSaving = false, error = e.message, errorCode = e.errorCode)
            } catch (e: Exception) {
                _state.value = _state.value.copy(isSaving = false, errorCode = "network")
            }
        }
    }

    internal companion object {
        /** What money repeats by: a day at most (the server refuses hourly and minute rules). */
        val FREQUENCIES = listOf(Frequency.Daily, Frequency.Weekly, Frequency.Monthly, Frequency.Yearly)

        /**
         * The rule as the server takes it: the builder's day parts (those a todo sends), from its first day at noon local
         * (an unambiguous "that day" whatever the DST), ending with its last day. Null when it falls on no day.
         */
        fun recurrence(state: BudgetAddUiState, timeZone: TimeZone): BudgetRecurrence? {
            val rule = state.rule
            val first = rule.firstDate ?: return null
            val parts = CreateTodoViewModel.dayParts(rule)
            return BudgetRecurrence(
                frequency = parts.frequency,
                startDate = first.atStartOfDayIn(timeZone).plus(12.hours),
                interval = rule.interval,
                byDayOfWeek = parts.byDay,
                byMonthDay = parts.byMonthDay,
                bySetPos = parts.bySetPos,
                byMonth = parts.byMonth,
                count = rule.count.takeIf { rule.endMode == EndMode.AfterCount },
                // Inclusive end of the picked day, so that day's occurrence counts.
                until = rule.untilDate?.takeIf { rule.endMode == EndMode.OnDate }
                    ?.let { LocalDateTime(it, END_OF_DAY).toInstant(timeZone) },
                timeZone = timeZone.id,
            )
        }

        /** "Every month · on days 15, 30", under the rule as it is built. */
        fun ruleInWords(state: BudgetAddUiState, s: StringsCatalog, timeZone: TimeZone): String? =
            if (!state.repeating) null else recurrence(state, timeZone)?.let { RuleSummary.describe(it, s) }
    }
}
