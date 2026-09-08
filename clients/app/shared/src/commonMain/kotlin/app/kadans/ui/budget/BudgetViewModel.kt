package app.kadans.ui.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
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
import app.kadans.api.model.UpdateRecurringTransaction
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime

data class BudgetUiState(
    val year: Int,
    val month: Int,
    val summary: MonthlySummaryResponse? = null,
    val settings: BudgetSettingsResponse? = null,
    val categories: List<CategoryResponse> = emptyList(),
    val recent: List<BudgetTransactionResponse> = emptyList(),
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

    fun createCategory(name: String, kind: CategoryKind, icon: String?) =
        act { api.budget.createCategory(CreateCategory(name, kind, icon?.ifBlank { null })) }

    fun setCategoryLimit(categoryId: String, limit: Double, currency: Currency) =
        act { api.budget.setCategoryBudget(categoryId, SetCategoryBudget(limit, currency)) }

    fun setBaseCurrency(base: Currency) = act { api.budget.setBaseCurrency(base) }

    fun setRate(currency: Currency, rateInBase: Double) = act { api.budget.setRate(currency, rateInBase) }

    fun deleteRate(currency: Currency) = act { api.budget.deleteRate(currency) }

    fun toggleRecurring(rule: RecurringTransactionResponse) =
        act {
            api.budget.updateRecurring(
                rule.id,
                UpdateRecurringTransaction(rule.amount, rule.categoryId, rule.note, isActive = !rule.isActive),
            )
        }

    fun deleteRecurring(id: String) = act { api.budget.deleteRecurring(id) }

    fun deleteTransaction(id: String) = act { api.budget.deleteTransaction(id) }

    private fun move(delta: Int) {
        val current = _state.value
        var year = current.year
        var month = current.month + delta
        if (month == 0) { month = 12; year -= 1 }
        if (month == 13) { month = 1; year += 1 }
        _state.value = current.copy(year = year, month = month, isLoading = true)
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
            val categories = api.budget.categories()
            val recent = api.budget.transactions(pageSize = 30)
            val recurring = runCatching { api.budget.recurring() }.getOrElse { emptyList() }
            val settings = runCatching { api.budget.settings() }.getOrNull()
            _state.value = _state.value.copy(
                summary = summary, categories = categories, recent = recent,
                recurring = recurring, settings = settings, isLoading = false,
            )
        } catch (e: KadansApiException) {
            _state.value = _state.value.copy(isLoading = false, error = e.message, errorCode = e.errorCode)
        } catch (e: Exception) {
            _state.value = _state.value.copy(isLoading = false, error = null, errorCode = "network")
        }
    }
}
