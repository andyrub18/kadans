package app.kadans.ui.billing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.api.model.BillingStore
import app.kadans.api.model.SubscriptionState
import app.kadans.api.model.SubscriptionStatusResponse
import app.kadans.billing.PurchaseOutcome
import app.kadans.billing.StoreBilling
import app.kadans.billing.StoreProduct
import app.kadans.billing.SubscriptionGate
import app.kadans.realtime.KadansRealtime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the paywall says under its buttons, besides an error. */
enum class PaywallNotice { Pending, NothingToRestore, StoreFailed }

/** Where the paywall leads: on to Home once paid up, or back to sign-in. */
enum class PaywallExit { Unlocked, SignedOut }

data class PaywallUiState(
    val isLoading: Boolean = true,
    val isBusy: Boolean = false,
    val status: SubscriptionStatusResponse? = null,
    /** The price and trial as the store sells them here; null when there is no store or nothing on sale. */
    val product: StoreProduct? = null,
    val manageUrl: String? = null,
    val notice: PaywallNotice? = null,
    val error: String? = null,
    val errorCode: String? = null,
    val exit: PaywallExit? = null,
) {
    val canBuy: Boolean get() = !isBusy && product != null && status?.accountHash?.isNotBlank() == true

    /** Only once the store holds a subscription for this account: it is where a payment is fixed or a plan cancelled. */
    val canManage: Boolean get() = manageUrl != null && status?.store == BillingStore.Google

    val canFake: Boolean get() = !isBusy && status?.fakeStore == true
}

/**
 * The paywall on a phone. Buying goes through the store; the store's purchase then goes to the server, which checks it
 * with the store before it counts. Nothing here decides access: the server's answer does.
 */
class PaywallViewModel(
    private val api: KadansApi,
    private val store: StoreBilling,
    private val gate: SubscriptionGate,
    private val realtime: KadansRealtime,
) : ViewModel() {
    private val _state = MutableStateFlow(PaywallUiState())
    val state: StateFlow<PaywallUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        try {
            val status = api.billing.status()
            if (!SubscriptionGate.blocks(status)) {
                _state.update { it.copy(isLoading = false, status = status, exit = PaywallExit.Unlocked) }
                return
            }
            val productId = status.googleProductId
            val product = if (store.isSupported && productId.isNotBlank()) runCatching { store.product(productId) }.getOrNull() else null
            _state.update {
                it.copy(
                    isLoading = false,
                    status = status,
                    product = product,
                    manageUrl = productId.takeIf { id -> id.isNotBlank() }?.let(store::manageUrl),
                    notice = PaywallNotice.Pending.takeIf { status.state == SubscriptionState.Pending },
                )
            }
        } catch (e: KadansApiException) {
            if (e.httpStatus == 401) _state.update { it.copy(exit = PaywallExit.SignedOut) }
            else _state.update { it.copy(isLoading = false, error = e.message, errorCode = e.errorCode) }
        } catch (_: Exception) {
            _state.update { it.copy(isLoading = false, errorCode = "network") }
        }
    }

    fun retry() {
        _state.update { it.copy(isLoading = true, error = null, errorCode = null) }
        viewModelScope.launch { load() }
    }

    /** The store's own purchase sheet; what it ends with is checked by the server. */
    fun subscribe() = busy {
        val status = _state.value.status ?: return@busy
        when (val outcome = store.purchase(status.googleProductId, status.accountHash)) {
            is PurchaseOutcome.Purchased -> settle(api.billing.linkGoogle(outcome.token))
            is PurchaseOutcome.Pending -> settle(api.billing.linkGoogle(outcome.token), otherwise = PaywallNotice.Pending)
            PurchaseOutcome.AlreadyOwned -> settle(gate.restore(status), otherwise = PaywallNotice.NothingToRestore)
            PurchaseOutcome.Cancelled -> Unit
            is PurchaseOutcome.Failed -> _state.update { it.copy(notice = PaywallNotice.StoreFailed) }
        }
    }

    /** A new phone or a reinstall: the store account's purchases, linked again. */
    fun restore() = busy {
        settle(gate.restore(api.billing.status()), otherwise = PaywallNotice.NothingToRestore)
    }

    /** Development only (the server must allow it): a trial without a store. */
    fun fakeTrial() = busy { settle(api.billing.fake()) }

    fun signOut() {
        viewModelScope.launch {
            realtime.stop()
            runCatching { api.auth.logout() }
            _state.update { it.copy(exit = PaywallExit.SignedOut) }
        }
    }

    private fun settle(status: SubscriptionStatusResponse, otherwise: PaywallNotice? = null) {
        if (!SubscriptionGate.blocks(status)) {
            _state.update { it.copy(status = status, exit = PaywallExit.Unlocked) }
            return
        }
        val notice = if (status.state == SubscriptionState.Pending) PaywallNotice.Pending else otherwise
        _state.update {
            it.copy(
                status = status,
                notice = notice,
                manageUrl = it.manageUrl ?: status.googleProductId.takeIf { id -> id.isNotBlank() }?.let(store::manageUrl),
            )
        }
    }

    private fun busy(action: suspend () -> Unit) {
        if (_state.value.isBusy) return
        _state.update { it.copy(isBusy = true, notice = null, error = null, errorCode = null) }
        viewModelScope.launch {
            try {
                action()
            } catch (e: KadansApiException) {
                if (e.httpStatus == 401) _state.update { it.copy(exit = PaywallExit.SignedOut) }
                else _state.update { it.copy(error = e.message, errorCode = e.errorCode) }
            } catch (_: Exception) {
                _state.update { it.copy(errorCode = "network") }
            } finally {
                _state.update { it.copy(isBusy = false) }
            }
        }
    }
}
