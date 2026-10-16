package app.kadans.ui.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.api.model.AccountResponse
import app.kadans.api.model.AccountType
import app.kadans.api.model.BudgetTransactionResponse
import app.kadans.api.model.CategoryKind
import app.kadans.api.model.CategoryResponse
import app.kadans.api.model.CreateAccount
import app.kadans.api.model.CreateCategory
import app.kadans.api.model.Currency
import app.kadans.api.model.BudgetSettingsResponse
import app.kadans.api.model.MonthlySummaryResponse
import app.kadans.api.model.RecurringTransactionResponse
import app.kadans.api.model.SetCategoryBudget
import app.kadans.api.model.UpdateAccount
import app.kadans.api.model.UpdateRecurringTransaction
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

data class BudgetUiState(
    val year: Int,
    val month: Int,
    val summary: MonthlySummaryResponse? = null,
    val settings: BudgetSettingsResponse? = null,
    /** Every category and account, archived ones too: the month's movements name theirs. */
    val categories: List<CategoryResponse> = emptyList(),
    val accounts: List<AccountResponse> = emptyList(),
    /** The movements of the month shown, newest first, [recentPage] pages of them. */
    val recent: List<BudgetTransactionResponse> = emptyList(),
    val recentPage: Int = 1,
    val recentHasMore: Boolean = false,
    val recurring: List<RecurringTransactionResponse> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val errorCode: String? = null,
    val actionError: String? = null,
    val actionErrorCode: String? = null,
)

class BudgetViewModel(private val api: KadansApi) : ViewModel() {
    private val _state: MutableStateFlow<BudgetUiState>
    val state: StateFlow<BudgetUiState>

    init {
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        _state = MutableStateFlow(BudgetUiState(year = today.year, month = today.month.number))
        state = _state.asStateFlow()
        refresh()
    }

    fun previousMonth() = move(-1)

    fun nextMonth() = move(1)

    fun refresh() {
        _state.value = _state.value.copy(isLoading = true, error = null, errorCode = null)
        viewModelScope.launch { load() }
    }

    fun createAccount(name: String, currency: Currency, type: AccountType, initialBalance: Double) =
        act { api.budget.createAccount(CreateAccount(name, currency, type, initialBalance)) }

    fun updateAccount(id: String, name: String, type: AccountType, isArchived: Boolean) =
        act { api.budget.updateAccount(id, UpdateAccount(name, type, isArchived)) }

    fun createCategory(name: String, kind: CategoryKind, icon: String?) =
        act { api.budget.createCategory(CreateCategory(name, kind, icon?.ifBlank { null })) }

    fun setCategoryLimit(categoryId: String, limit: Double, currency: Currency) =
        act { api.budget.setCategoryBudget(categoryId, SetCategoryBudget(limit, currency)) }

    fun setBaseCurrency(base: Currency) = act { api.budget.setBaseCurrency(base) }

    fun setRate(currency: Currency, rateInBase: Double) = act { api.budget.setRate(currency, rateInBase) }

    fun deleteRate(currency: Currency) = act { api.budget.deleteRate(currency) }

    // A transfer between two currencies sends its received amount back, or the server refuses the pause.
    fun toggleRecurring(rule: RecurringTransactionResponse) =
        act {
            api.budget.updateRecurring(
                rule.id,
                UpdateRecurringTransaction(rule.amount, rule.categoryId, rule.note, isActive = !rule.isActive, transferAmount = rule.transferAmount),
            )
        }

    /** What the rule's next bookings carry; [received] only between two currencies. Paused or not, it stays so. */
    fun updateRecurring(rule: RecurringTransactionResponse, amount: Double, received: Double?, categoryId: String?, note: String) =
        act {
            api.budget.updateRecurring(
                rule.id,
                UpdateRecurringTransaction(amount, categoryId, note.trim(), isActive = rule.isActive, transferAmount = received),
            )
        }

    fun deleteRecurring(id: String) = act { api.budget.deleteRecurring(id) }

    /** The month's next page of movements. */
    fun showMore() {
        val current = _state.value
        val zone = current.summary?.timeZoneId ?: return
        val (from, to) = monthWindow(current.year, current.month, zone)
        viewModelScope.launch {
            try {
                val next = api.budget.transactions(from = from, to = to, page = current.recentPage + 1, pageSize = PAGE_SIZE)
                val shown = _state.value
                if (shown.year != current.year || shown.month != current.month) return@launch
                _state.value = shown.copy(
                    // A movement recorded meanwhile shifts the pages by one: never show one twice.
                    recent = (shown.recent + next).distinctBy { it.id },
                    recentPage = current.recentPage + 1,
                    recentHasMore = next.size == PAGE_SIZE,
                )
            } catch (e: KadansApiException) {
                _state.value = _state.value.copy(actionError = e.message, actionErrorCode = e.errorCode)
            } catch (e: Exception) {
                _state.value = _state.value.copy(actionError = null, actionErrorCode = "network")
            }
        }
    }

    private fun move(delta: Int) {
        val current = _state.value
        var year = current.year
        var month = current.month + delta
        if (month == 0) { month = 12; year -= 1 }
        if (month == 13) { month = 1; year += 1 }
        _state.value = current.copy(year = year, month = month, recentPage = 1, isLoading = true)
        viewModelScope.launch { load() }
    }

    private fun act(action: suspend () -> Any?) {
        viewModelScope.launch {
            try {
                action()
                _state.value = _state.value.copy(actionError = null, actionErrorCode = null)
                load()
            } catch (e: KadansApiException) {
                _state.value = _state.value.copy(actionError = e.message, actionErrorCode = e.errorCode)
            } catch (e: Exception) {
                _state.value = _state.value.copy(actionError = null, actionErrorCode = "network")
            }
        }
    }

    private suspend fun load() {
        val snapshot = _state.value
        try {
            val summary = api.budget.summary(snapshot.year, snapshot.month)
            val categories = api.budget.categories(includeArchived = true)
            val accounts = api.budget.accounts(includeArchived = true)
            // The month the screen shows, bounded as its totals are (the account's time zone), as many pages as were open.
            val (from, to) = monthWindow(snapshot.year, snapshot.month, summary.timeZoneId)
            val recent = (1..snapshot.recentPage).flatMap { page ->
                api.budget.transactions(from = from, to = to, page = page, pageSize = PAGE_SIZE)
            }.distinctBy { it.id }
            val recurring = runCatching { api.budget.recurring() }.getOrElse { emptyList() }
            val settings = runCatching { api.budget.settings() }.getOrNull()
            _state.value = _state.value.copy(
                summary = summary, categories = categories, accounts = accounts, recent = recent,
                recentHasMore = recent.size >= snapshot.recentPage * PAGE_SIZE,
                recurring = recurring, settings = settings, isLoading = false,
            )
        } catch (e: KadansApiException) {
            _state.value = _state.value.copy(isLoading = false, error = e.message, errorCode = e.errorCode)
        } catch (e: Exception) {
            _state.value = _state.value.copy(isLoading = false, error = null, errorCode = "network")
        }
    }

    internal companion object {
        /** Movements a page; the month's list shows one, then more on demand. */
        const val PAGE_SIZE = 50

        /** A month from its 1st to the next, in [timeZoneId] (the summary's; the device's when it is unknown here). */
        fun monthWindow(year: Int, month: Int, timeZoneId: String): Pair<Instant, Instant> {
            val zone = runCatching { TimeZone.of(timeZoneId) }.getOrElse { TimeZone.currentSystemDefault() }
            val first = LocalDate(year, month, 1)
            return first.atStartOfDayIn(zone) to first.plus(1, DateTimeUnit.MONTH).atStartOfDayIn(zone)
        }
    }
}
