package app.kadans.ui.budget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kadans.api.model.AccountType
import app.kadans.api.model.BudgetTransactionKind
import app.kadans.api.model.CategoryKind
import app.kadans.api.model.CategorySpend
import app.kadans.api.model.Currency
import app.kadans.i18n.LocalStrings
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun BudgetScreen(
    onAddTransaction: () -> Unit,
    onBack: () -> Unit,
    viewModel: BudgetViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val s = LocalStrings.current
    var creatingAccount by remember { mutableStateOf(false) }
    var creatingCategory by remember { mutableStateOf(false) }
    var editingRate by remember { mutableStateOf(false) }
    var limitFor by remember { mutableStateOf<CategorySpend?>(null) }
    var editingAccount by remember { mutableStateOf<app.kadans.api.model.AccountResponse?>(null) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("← " + s.back) }
                Text(
                    s.monthNames[state.month - 1] + " " + state.year,
                    style = MaterialTheme.typography.titleLarge,
                )
                Row {
                    TextButton(onClick = viewModel::previousMonth) { Text("‹") }
                    TextButton(onClick = viewModel::nextMonth) { Text("›") }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddTransaction) { Text("+", style = MaterialTheme.typography.headlineSmall) }
        },
    ) { padding ->
        val summary = state.summary
        when {
            state.isLoading && summary == null ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.error != null || state.errorCode != null ->
                Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(s.errorFor(state.errorCode, state.error), color = MaterialTheme.colorScheme.error)
                    Button(onClick = viewModel::refresh, modifier = Modifier.padding(top = 12.dp)) { Text(s.retry) }
                }
            summary != null -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.actionError != null || state.actionErrorCode != null) {
                    item { Text(s.errorFor(state.actionErrorCode, state.actionError), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }

                // ---- combined estimate: only worth showing once a second currency exists ----
                val currencyCount = summary.accounts.map { it.currency }.distinct().size
                if (currencyCount > 1) summary.combined?.let { combined ->
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(s.atYourRate, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(s.totalBalanceLabel + ": " + formatMoney(combined.totalBalance, combined.baseCurrency), style = MaterialTheme.typography.titleMedium)
                                Text("${s.income}: ${formatAmount(combined.income)}  ·  ${s.expense}: ${formatAmount(combined.expense)}  ·  ${s.netLabel}: ${formatAmount(combined.net)}", style = MaterialTheme.typography.bodySmall)
                                if (combined.missingRates.isNotEmpty()) {
                                    Text(
                                        s.missingRatesLabel + " " + combined.missingRates.joinToString { it.name.uppercase() },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
                items(summary.totals, key = { "tot-" + it.currency.name }) { totals ->
                    Text(
                        "${totals.currency.name.uppercase()} — ${s.income}: ${formatAmount(totals.income)} · ${s.expense}: ${formatAmount(totals.expense)} · ${s.netLabel}: ${formatAmount(totals.net)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                item {
                    TextButton(onClick = { editingRate = true }) { Text(s.exchangeRateTitle) }
                }

                // ---- accounts ----
                item { HorizontalDivider() }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(s.accountsSection, style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { creatingAccount = true }) { Text(s.newAccount) }
                    }
                }
                items(summary.accounts, key = { it.id }) { account ->
                    Card(onClick = { editingAccount = account }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text(account.name, style = MaterialTheme.typography.titleSmall)
                                Text(s.accountTypeName(account.type), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(formatMoney(account.balance, account.currency), style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }

                // ---- categories: spend vs limit ----
                item { HorizontalDivider() }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(s.categoriesSection, style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { creatingCategory = true }) { Text(s.newCategory) }
                    }
                }
                items(summary.categories, key = { "sp-" + it.categoryId + it.currency.name }) { spend ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text((spend.icon?.plus(" ") ?: "") + spend.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                formatMoney(spend.amount, spend.currency) + (spend.monthlyLimit?.let { " / " + formatAmount(it) } ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (spend.monthlyLimit != null && spend.amount > spend.monthlyLimit)
                                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        spend.monthlyLimit?.let { limit ->
                            LinearProgressIndicator(
                                progress = { (spend.amount / limit).toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            )
                        }
                        if (spend.kind == CategoryKind.Expense) {
                            TextButton(onClick = { limitFor = spend }) { Text(s.limitLabel, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }

                // ---- recurring ----
                if (state.recurring.isNotEmpty()) {
                    item { HorizontalDivider() }
                    item { Text(s.recurringSection, style = MaterialTheme.typography.titleMedium) }
                    items(state.recurring, key = { "rec-" + it.id }) { rule ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.padding(end = 8.dp)) {
                                Text("${s.transactionKindName(rule.kind)} · ${formatMoney(rule.amount, rule.currency)}" + (rule.note.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                                rule.nextAt?.let {
                                    Text(it.toLocalDateTime(TimeZone.currentSystemDefault()).date.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Row {
                                TextButton(onClick = { viewModel.toggleRecurring(rule) }) { Text(if (rule.isActive) s.pause else s.resume) }
                                TextButton(onClick = { viewModel.deleteRecurring(rule.id) }) { Text(s.deleteWord, color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }

                // ---- recent movements ----
                item { HorizontalDivider() }
                item { Text(s.recentTransactions, style = MaterialTheme.typography.titleMedium) }
                if (state.recent.isEmpty()) {
                    item { Text(s.noTransactionsYet, style = MaterialTheme.typography.bodyMedium) }
                }
                items(state.recent, key = { "tx-" + it.id }) { tx ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.padding(end = 8.dp)) {
                            Text(
                                (if (tx.kind == BudgetTransactionKind.Expense) "−" else if (tx.kind == BudgetTransactionKind.Income) "+" else "⇄") +
                                    " " + formatMoney(tx.amount, tx.currency) + (tx.note.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                tx.occurredAt.toLocalDateTime(TimeZone.currentSystemDefault()).date.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { viewModel.deleteTransaction(tx.id) }) { Text(s.deleteWord, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
    }

    if (creatingAccount) {
        NewAccountDialog(onCreate = { name, currency, type, balance ->
            viewModel.createAccount(name, currency, type, balance); creatingAccount = false
        }, onDismiss = { creatingAccount = false })
    }
    if (creatingCategory) {
        NewCategoryDialog(onCreate = { name, kind, icon ->
            viewModel.createCategory(name, kind, icon); creatingCategory = false
        }, onDismiss = { creatingCategory = false })
    }
    if (editingRate) {
        CurrenciesDialog(
            settings = state.settings ?: app.kadans.api.model.BudgetSettingsResponse(),
            usedCurrencies = state.summary?.accounts?.map { it.currency }?.toSet() ?: emptySet(),
            onSetBase = viewModel::setBaseCurrency,
            onSetRate = viewModel::setRate,
            onDismiss = { editingRate = false },
        )
    }
    editingAccount?.let { account ->
        EditAccountDialog(
            account = account,
            onSave = { name, type, archived ->
                viewModel.updateAccount(account.id, name, type, archived); editingAccount = null
            },
            onDismiss = { editingAccount = null },
        )
    }
    limitFor?.let { spend ->
        LimitDialog(
            spend = spend,
            onSet = { limit -> viewModel.setCategoryLimit(spend.categoryId, limit, spend.currency); limitFor = null },
            onDismiss = { limitFor = null },
        )
    }
}

@Composable
private fun NewAccountDialog(onCreate: (String, Currency, AccountType, Double) -> Unit, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    var name by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf(Currency.Htg) }
    var type by remember { mutableStateOf(AccountType.Cash) }
    var balance by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.newAccount) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(s.accountName) }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Currency.entries.forEach { c ->
                        FilterChip(selected = currency == c, onClick = { currency = c }, label = { Text(c.name.uppercase()) })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AccountType.entries.forEach { t ->
                        FilterChip(selected = type == t, onClick = { type = t }, label = { Text(s.accountTypeName(t)) })
                    }
                }
                OutlinedTextField(value = balance, onValueChange = { balance = it }, label = { Text(s.initialBalanceLabel) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim(), currency, type, balance.replace(",", ".").toDoubleOrNull() ?: 0.0) },
                enabled = name.isNotBlank(),
            ) { Text(s.create) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}

@Composable
private fun EditAccountDialog(
    account: app.kadans.api.model.AccountResponse,
    onSave: (String, AccountType, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    var name by remember { mutableStateOf(account.name) }
    var type by remember { mutableStateOf(account.type) }
    var archived by remember { mutableStateOf(account.isArchived) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.editAccount) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(s.accountName) }, singleLine = true)
                // The currency is the account's identity: change it by making a new account.
                Text(s.currencyLabel + ": " + account.currency.name.uppercase(), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AccountType.entries.forEach { t ->
                        FilterChip(selected = type == t, onClick = { type = t }, label = { Text(s.accountTypeName(t)) })
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(s.archivedLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
                    androidx.compose.material3.Switch(checked = archived, onCheckedChange = { archived = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), type, archived) }, enabled = name.isNotBlank()) { Text(s.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}

@Composable
private fun NewCategoryDialog(onCreate: (String, CategoryKind, String?) -> Unit, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(CategoryKind.Expense) }
    var icon by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.newCategory) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(s.categoryName) }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = kind == CategoryKind.Expense, onClick = { kind = CategoryKind.Expense }, label = { Text(s.expense) })
                    FilterChip(selected = kind == CategoryKind.Income, onClick = { kind = CategoryKind.Income }, label = { Text(s.income) })
                }
                OutlinedTextField(value = icon, onValueChange = { icon = it }, label = { Text(s.iconOptional) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.trim(), kind, icon.trim().ifBlank { null }) }, enabled = name.isNotBlank()) { Text(s.create) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}

@Composable
private fun CurrenciesDialog(
    settings: app.kadans.api.model.BudgetSettingsResponse,
    usedCurrencies: Set<Currency>,
    onSetBase: (Currency) -> Unit,
    onSetRate: (Currency, Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    var revealed by remember { mutableStateOf(setOf<Currency>()) }
    val base = settings.baseCurrency
    val listed = (usedCurrencies + settings.rates.map { it.currency } + revealed - base).sortedBy { it.ordinal }
    val others = Currency.entries.filter { it != base && it !in listed }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.exchangeRateTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.baseCurrencyLabel, style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Currency.entries.forEach { c ->
                        FilterChip(selected = base == c, onClick = { onSetBase(c) }, label = { Text(c.name.uppercase()) })
                    }
                }
                Text(s.rateHint, style = MaterialTheme.typography.bodySmall)
                listed.forEach { c ->
                    RateRow(
                        currency = c,
                        base = base,
                        current = settings.rates.firstOrNull { it.currency == c }?.rateInBase,
                        onSet = { onSetRate(c, it) },
                    )
                }
                if (others.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        others.forEach { c ->
                            TextButton(onClick = { revealed = revealed + c }) { Text("+ " + c.name.uppercase()) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(s.close) } },
    )
}

@Composable
private fun RateRow(currency: Currency, base: Currency, current: Double?, onSet: (Double) -> Unit) {
    val s = LocalStrings.current
    var value by remember(currency, current) { mutableStateOf(current?.let { formatAmount(it).replace(" ", "") } ?: "") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("1 " + currency.name.uppercase() + " =", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Text(base.name.uppercase(), style = MaterialTheme.typography.bodyMedium)
        TextButton(
            onClick = { value.replace(",", ".").toDoubleOrNull()?.let(onSet) },
            enabled = (value.replace(",", ".").toDoubleOrNull() ?: 0.0) > 0.0,
        ) { Text(s.save) }
    }
}

@Composable
private fun LimitDialog(spend: CategorySpend, onSet: (Double) -> Unit, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    var value by remember { mutableStateOf(spend.monthlyLimit?.toString() ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text((spend.icon?.plus(" ") ?: "") + spend.name) },
        text = {
            OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(s.limitLabel + " (" + spend.currency.name.uppercase() + ")") }, singleLine = true)
        },
        confirmButton = {
            TextButton(
                onClick = { value.replace(",", ".").toDoubleOrNull()?.let(onSet) },
                enabled = (value.replace(",", ".").toDoubleOrNull() ?: 0.0) > 0.0,
            ) { Text(s.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}
