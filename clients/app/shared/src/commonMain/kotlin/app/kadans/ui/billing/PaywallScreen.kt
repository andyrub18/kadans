package app.kadans.ui.billing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.kadans.config.LegalLinks
import app.kadans.i18n.LocalStrings
import org.koin.compose.viewmodel.koinViewModel

/**
 * The paywall: what the subscription gives, its price and trial as the store sells them here, how it renews and how to
 * cancel, the Terms and Privacy links, and the way back in for someone who already paid (Restore). The stores require
 * all of it on the screen that sells.
 */
@Composable
fun PaywallScreen(
    onUnlocked: () -> Unit,
    onSignedOut: () -> Unit,
    viewModel: PaywallViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val s = LocalStrings.current
    val p = s.paywall
    val uriHandler = LocalUriHandler.current
    fun open(url: String) = runCatching { uriHandler.openUri(url) }

    LaunchedEffect(state.exit) {
        when (state.exit) {
            PaywallExit.Unlocked -> onUnlocked()
            PaywallExit.SignedOut -> onSignedOut()
            null -> Unit
        }
    }

    if (state.isLoading || state.exit != null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Kadans", style = MaterialTheme.typography.displaySmall)
            Text(p.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(p.intro, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(p.benefitReminders, p.benefitFocus, p.benefitBudget).forEach { Text("✓  $it", style = MaterialTheme.typography.bodyMedium) }
            }

            val product = state.product
            if (product != null) {
                val trial = product.trialDays
                Text(
                    if (trial != null) p.trialPrice(trial, product.monthlyPrice) else p.price(product.monthlyPrice),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = viewModel::subscribe, enabled = state.canBuy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (trial != null) p.startTrial else p.subscribe)
                }
                Text(p.renewalTerms, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (state.status != null) {
                Text(p.unavailable, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                OutlinedButton(onClick = viewModel::retry, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) { Text(s.retry) }
            }

            when (state.notice) {
                PaywallNotice.Pending -> Text(p.pending, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                PaywallNotice.NothingToRestore -> Text(p.nothingToRestore, textAlign = TextAlign.Center)
                PaywallNotice.StoreFailed -> Text(p.storeFailed, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                null -> Unit
            }
            if (state.error != null || state.errorCode != null) {
                Text(s.errorFor(state.errorCode, state.error), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                if (state.status == null) {
                    Button(onClick = viewModel::retry, modifier = Modifier.fillMaxWidth()) { Text(s.retry) }
                }
            }

            if (state.status != null) {
                OutlinedButton(onClick = viewModel::restore, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) { Text(p.restore) }
            }
            state.manageUrl?.takeIf { state.canManage }?.let { url ->
                OutlinedButton(onClick = { open(url) }, modifier = Modifier.fillMaxWidth()) { Text(p.manage) }
            }
            if (state.canFake) {
                TextButton(onClick = viewModel::fakeTrial, modifier = Modifier.fillMaxWidth()) { Text(p.devFakeTrial) }
            }
            if (state.isBusy) CircularProgressIndicator()

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { open(LegalLinks.TERMS) }) { Text(p.terms) }
                TextButton(onClick = { open(LegalLinks.PRIVACY) }) { Text(p.privacy) }
            }
            TextButton(onClick = viewModel::signOut, enabled = !state.isBusy) { Text(s.signOut) }
        }
    }
}
