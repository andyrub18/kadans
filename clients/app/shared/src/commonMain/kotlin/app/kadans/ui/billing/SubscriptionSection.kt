package app.kadans.ui.billing

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kadans.api.KadansApi
import app.kadans.api.model.BillingStore
import app.kadans.api.model.SubscriptionState
import app.kadans.api.model.SubscriptionStatusResponse
import app.kadans.billing.StoreBilling
import app.kadans.billing.SubscriptionGate
import app.kadans.i18n.LocalStrings
import app.kadans.i18n.PaywallStrings
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.viewmodel.koinViewModel

/** The subscription in Settings, on phones only (desktop is free): where it stands, and the store page to manage it. */
class SubscriptionSettingsViewModel(
    private val api: KadansApi,
    private val store: StoreBilling,
    gate: SubscriptionGate,
) : ViewModel() {
    private val _status = MutableStateFlow<SubscriptionStatusResponse?>(null)

    /** Null while unknown, on desktop, or when the server could not be reached: the section is then left out. */
    val status: StateFlow<SubscriptionStatusResponse?> = _status.asStateFlow()

    init {
        if (gate.appliesHere) viewModelScope.launch { runCatching { api.billing.status() }.onSuccess { _status.value = it } }
    }

    fun manageUrl(status: SubscriptionStatusResponse): String? =
        status.googleProductId.takeIf { status.store == BillingStore.Google && it.isNotBlank() }?.let(store::manageUrl)

    companion object {
        /** Shown once subscriptions are sold, or when this account holds one anyway. */
        fun shows(status: SubscriptionStatusResponse): Boolean = status.required || status.store != null

        fun describe(status: SubscriptionStatusResponse, p: PaywallStrings, date: (Instant) -> String): String {
            // A free account that also bought one (before it was made free) is still told it has nothing to pay.
            if (status.freeAccess) return p.freeAccess
            val end = status.expiresAt?.let(date) ?: "—"
            return when (status.state) {
                null, SubscriptionState.Expired, SubscriptionState.Revoked -> p.notSubscribed
                SubscriptionState.Pending -> p.pending
                SubscriptionState.Trial -> if (status.autoRenewing) p.trialUntil(end) else p.endsOn(end)
                SubscriptionState.Active -> if (status.autoRenewing) p.renewsOn(end) else p.endsOn(end)
                SubscriptionState.Canceled -> p.endsOn(end)
                SubscriptionState.GracePeriod, SubscriptionState.OnHold -> p.paymentProblem
                SubscriptionState.Paused -> p.paused
            }
        }
    }
}

@Composable
fun SubscriptionSection(viewModel: SubscriptionSettingsViewModel = koinViewModel()) {
    val status by viewModel.status.collectAsState()
    val current = status?.takeIf(SubscriptionSettingsViewModel::shows) ?: return
    val s = LocalStrings.current
    val p = s.paywall
    val uriHandler = LocalUriHandler.current
    val zone = androidx.compose.runtime.remember { TimeZone.currentSystemDefault() }

    Text(p.settingsSection, style = MaterialTheme.typography.titleMedium)
    val line = SubscriptionSettingsViewModel.describe(current, p) { s.deletion.date(it.toLocalDateTime(zone).date) }
    val troubled = current.state == SubscriptionState.GracePeriod || current.state == SubscriptionState.OnHold
    Text(line, style = MaterialTheme.typography.bodyMedium, color = if (troubled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    viewModel.manageUrl(current)?.let { url ->
        OutlinedButton(onClick = { runCatching { uriHandler.openUri(url) } }, modifier = Modifier.fillMaxWidth()) { Text(p.manage) }
    }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
}
