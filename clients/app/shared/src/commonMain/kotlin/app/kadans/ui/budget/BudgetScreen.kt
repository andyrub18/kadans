package app.kadans.ui.budget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import app.kadans.api.model.CategoryLimitStatus
import app.kadans.api.model.LimitPeriod
import kotlin.time.Clock
import kotlinx.datetime.number
import kotlinx.datetime.todayIn
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kadans.api.BudgetApi
import app.kadans.api.model.AccountResponse
import app.kadans.api.model.AccountType
import app.kadans.api.model.BudgetTransactionKind
import app.kadans.api.model.BudgetTransactionResponse
import app.kadans.api.model.CategoryKind
import app.kadans.api.model.CategoryResponse
import app.kadans.api.model.CategorySpend
import app.kadans.api.model.Currency
import app.kadans.api.model.RecurrenceRuleResponse
import app.kadans.api.model.RecurringTransactionResponse
import app.kadans.i18n.StringsCatalog
import app.kadans.ui.todos.RuleSummary
import app.kadans.i18n.LocalStrings
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun BudgetScreen(
    onAddTransaction: () -> Unit,
    onEditTransaction: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: BudgetViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val s = LocalStrings.current
    var creatingAccount by remember { mutableStateOf(false) }
    var creatingCategory by remember { mutableStateOf(false) }
    var editingRate by remember { mutableStateOf(false) }
    var limitFor by remember { mutableStateOf<CategoryResponse?>(null) }
    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
    var editingAccount by remember { mutableStateOf<app.kadans.api.model.AccountResponse?>(null) }
    var editingRule by remember { mutableStateOf<RecurringTransactionResponse?>(null) }

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

                // ---- categories: the month's spending, and each limit in its period ----
                item { HorizontalDivider() }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(s.categoriesSection, style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { creatingCategory = true }) { Text(s.newCategory) }
                    }
                }
                // Every expense category (a limit can be set before anything is spent), then the incomes of the month.
                val shownCategories = state.categories.filter { !it.isArchived }
                    .filter { c -> c.kind == CategoryKind.Expense || summary.categories.any { it.categoryId == c.id } }
                    .sortedWith(compareBy({ it.kind != CategoryKind.Expense }, { it.name.lowercase() }))
                items(shownCategories, key = { "cat-" + it.id }) { category ->
                    CategoryRow(
                        category = category,
                        spends = summary.categories.filter { it.categoryId == category.id },
                        limit = summary.limits.firstOrNull { it.categoryId == category.id },
                        // A weekly limit is this week's: on another month there is no bar to show.
                        showsWeek = state.year == today.year && state.month == today.month.number,
                        onClick = if (category.kind == CategoryKind.Expense) ({ limitFor = category }) else null,
                    )
                }

                // ---- recurring ----
                if (state.recurring.isNotEmpty()) {
                    item { HorizontalDivider() }
                    item { Text(s.recurringSection, style = MaterialTheme.typography.titleMedium) }
                    items(state.recurring, key = { "rec-" + it.id }) { rule ->
                        Row(
                            Modifier.fillMaxWidth().clickable { editingRule = rule }.padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    "${s.transactionKindName(rule.kind)} · ${formatMoney(rule.amount, rule.currency)}" +
                                        received(rule.kind, rule.currency, rule.transferAccountId, rule.transferAmount, state.accounts) +
                                        (rule.note.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                // How it repeats, as the form said it ("Every month · on days 15, 30"), then the next one.
                                Text(
                                    RuleSummary.describe(rule.asRule(), s, TimeZone.currentSystemDefault(), withTime = false),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    listOfNotNull(
                                        rule.nextAt?.toLocalDateTime(TimeZone.currentSystemDefault())?.date?.let { s.movements.next(it.toString()) },
                                        accountsLine(rule.accountId, rule.transferAccountId, state.accounts),
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { viewModel.toggleRecurring(rule) }) { Text(if (rule.isActive) s.pause else s.resume) }
                        }
                    }
                }

                // ---- the month's movements: one opens to correct it ----
                item { HorizontalDivider() }
                item { Text(s.movements.listTitle, style = MaterialTheme.typography.titleMedium) }
                if (state.recent.isEmpty()) {
                    item { Text(s.noTransactionsYet, style = MaterialTheme.typography.bodyMedium) }
                }
                items(state.recent, key = { "tx-" + it.id }) { tx ->
                    MovementRow(tx, state.accounts, state.categories, s, onClick = { onEditTransaction(tx.id) })
                }
                if (state.recentHasMore) {
                    item {
                        TextButton(onClick = viewModel::showMore, modifier = Modifier.fillMaxWidth()) { Text(s.movements.showMore) }
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
    editingRule?.let { rule ->
        RuleDialog(
            rule = rule,
            accounts = state.accounts,
            categories = state.categories,
            onSave = { amount, received, categoryId, note ->
                viewModel.updateRecurring(rule, amount, received, categoryId, note); editingRule = null
            },
            onDelete = { viewModel.deleteRecurring(rule.id); editingRule = null },
            onDismiss = { editingRule = null },
        )
    }
    limitFor?.let { category ->
        LimitDialog(
            category = category,
            existing = state.summary?.limits?.firstOrNull { it.categoryId == category.id },
            baseCurrency = state.settings?.baseCurrency ?: Currency.Htg,
            onSet = { amount, currency, period ->
                viewModel.setCategoryLimit(category.id, amount, currency, period); limitFor = null
            },
            onRemove = { viewModel.removeCategoryLimit(category.id); limitFor = null },
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
    var value by remember(currency, current) { mutableStateOf(current?.let(::editableAmount) ?: "") }
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

/** Near a limit (80%): amber, legible on the theme's light and dark surfaces (Material has no warning colour). */
@Composable
private fun nearLimitColor(): Color = if (isSystemInDarkTheme()) Color(0xFFFFB951) else Color(0xFF8A5100)

/**
 * A category: what it took this month per currency and, with a limit, a bar of its period's use in the limit's
 * currency (other currencies at the person's rates, said; those without a rate left out, said). Amber from 80%, red at
 * 100%. An expense category opens its limit.
 */
@Composable
private fun CategoryRow(
    category: CategoryResponse,
    spends: List<CategorySpend>,
    limit: CategoryLimitStatus?,
    showsWeek: Boolean,
    onClick: (() -> Unit)?,
) {
    val s = LocalStrings.current
    Column(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text((category.icon?.plus(" ") ?: "") + category.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 8.dp))
            Text(
                spends.sortedByDescending { it.amount }.joinToString(" · ") { formatMoney(it.amount, it.currency) }.ifEmpty { "—" },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        val name = when (limit?.period) {
            LimitPeriod.Week -> s.limits.weeklyLimit
            LimitPeriod.Month -> s.limits.monthlyLimit
            LimitPeriod.Year -> s.limits.yearlyLimit
            null -> null
        }
        when {
            limit == null || name == null ->
                if (onClick != null) Text(s.limits.set, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            limit.period == LimitPeriod.Week && !showsWeek ->
                Text("$name: " + formatMoney(limit.amount, limit.currency), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                val color = when {
                    limit.percent >= 100 -> MaterialTheme.colorScheme.error
                    limit.percent >= 80 -> nearLimitColor()
                    else -> MaterialTheme.colorScheme.primary
                }
                LinearProgressIndicator(
                    progress = { (limit.percent / 100f).coerceIn(0f, 1f) },
                    color = color,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        s.limits.percent(limit.percent) + " · " + name,
                        style = MaterialTheme.typography.bodySmall,
                        color = color,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    Text(
                        (if (limit.converted.isNotEmpty()) "≈ " else "") + formatAmount(limit.spent) + " / " + formatMoney(limit.amount, limit.currency),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (limit.converted.isNotEmpty()) {
                    Text(
                        s.limits.converted(limit.converted.joinToString(", ") { it.name.uppercase() }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (limit.missingRates.isNotEmpty()) {
                    Text(
                        s.limits.missing(limit.missingRates.joinToString(", ") { it.name.uppercase() }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * A category's limit: its amount and period, and its currency when it is first set (the base currency to start with).
 * A limit keeps its currency: another means removing it and setting a new one, so nothing is converted after the fact.
 */
@Composable
private fun LimitDialog(
    category: CategoryResponse,
    existing: CategoryLimitStatus?,
    baseCurrency: Currency,
    onSet: (Double, Currency, LimitPeriod) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    var amount by remember { mutableStateOf(existing?.amount?.let(::editableAmount) ?: "") }
    var currency by remember { mutableStateOf(existing?.currency ?: baseCurrency) }
    var period by remember { mutableStateOf(existing?.period ?: LimitPeriod.Month) }
    val value = amount.replace(",", ".").toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text((category.icon?.plus(" ") ?: "") + category.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = amountInput(it) },
                    label = { Text(s.amountLabel + " (" + currency.name.uppercase() + ")") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (existing == null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Currency.entries.forEach { c ->
                            FilterChip(selected = currency == c, onClick = { currency = c }, label = { Text(c.name.uppercase()) })
                        }
                    }
                } else {
                    Text(s.limits.currencyFixed(currency.name.uppercase()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(LimitPeriod.Week to s.limits.weekly, LimitPeriod.Month to s.limits.monthly, LimitPeriod.Year to s.limits.yearly)
                        .forEach { (p, label) -> FilterChip(selected = period == p, onClick = { period = p }, label = { Text(label) }) }
                }
                Text(s.limits.alertNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (existing != null) {
                    TextButton(onClick = onRemove) { Text(s.limits.remove, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { value?.let { onSet(it, currency, period) } }, enabled = (value ?: 0.0) > 0.0) { Text(s.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}

/** A budget rule as the todo's words read one: its schedule's parts are the same engine's. */
private fun RecurringTransactionResponse.asRule() =
    RecurrenceRuleResponse(rrule, timeZoneId, startDate, frequency, interval, count, until)

/** "Cash" or, for a transfer, "Cash → Savings". */
private fun accountsLine(accountId: String, transferAccountId: String?, accounts: List<AccountResponse>): String? {
    fun name(id: String) = accounts.firstOrNull { it.id == id }?.name
    val source = name(accountId) ?: return null
    return transferAccountId?.let { name(it) }?.let { "$source → $it" } ?: source
}

/** " → 37.50 USD" after a transfer's amount when the destination counts in another currency. */
private fun received(
    kind: BudgetTransactionKind,
    currency: Currency,
    transferAccountId: String?,
    transferAmount: Double?,
    accounts: List<AccountResponse>,
): String {
    if (kind != BudgetTransactionKind.Transfer || transferAmount == null) return ""
    val destination = accounts.firstOrNull { it.id == transferAccountId }?.currency ?: return ""
    return if (destination == currency) "" else " → " + formatMoney(transferAmount, destination)
}

/** One movement of the month: what, then when, where and why; a tap opens it to correct it. */
@Composable
private fun MovementRow(
    tx: BudgetTransactionResponse,
    accounts: List<AccountResponse>,
    categories: List<CategoryResponse>,
    s: StringsCatalog,
    onClick: () -> Unit,
) {
    val sign = when (tx.kind) {
        BudgetTransactionKind.Expense -> "−"
        BudgetTransactionKind.Income -> "+"
        BudgetTransactionKind.Transfer -> "⇄"
    }
    val category = categories.firstOrNull { it.id == tx.categoryId }?.let { (it.icon?.plus(" ") ?: "") + it.name }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                "$sign " + formatMoney(tx.amount, tx.currency) +
                    received(tx.kind, tx.currency, tx.transferAccountId, tx.transferAmount, accounts) +
                    (category?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                listOfNotNull(
                    tx.occurredAt.toLocalDateTime(TimeZone.currentSystemDefault()).date.toString(),
                    accountsLine(tx.accountId, tx.transferAccountId, accounts),
                    tx.note.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A recurring movement changed: what its next bookings carry (amount, received amount between two currencies,
 * category, note), or the rule deleted. Where and when it books stays: another schedule is a new rule.
 */
@Composable
private fun RuleDialog(
    rule: RecurringTransactionResponse,
    accounts: List<AccountResponse>,
    categories: List<CategoryResponse>,
    onSave: (amount: Double, received: Double?, categoryId: String?, note: String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    val transfer = rule.kind == BudgetTransactionKind.Transfer
    val destination = accounts.firstOrNull { it.id == rule.transferAccountId }
    val cross = transfer && destination != null && destination.currency != rule.currency
    var amount by remember { mutableStateOf(editableAmount(rule.amount)) }
    var received by remember { mutableStateOf(rule.transferAmount?.takeIf { cross }?.let(::editableAmount) ?: "") }
    var categoryId by remember { mutableStateOf(rule.categoryId) }
    var note by remember { mutableStateOf(rule.note) }
    var confirmingDelete by remember { mutableStateOf(false) }
    fun parsed(text: String) = text.replace(",", ".").toDoubleOrNull()
    val kind = if (rule.kind == BudgetTransactionKind.Income) CategoryKind.Income else CategoryKind.Expense
    val matching = if (transfer) emptyList()
    else categories.filter { it.kind == kind && (!it.isArchived || it.id == rule.categoryId) }
    val valid = (parsed(amount) ?: 0.0) > 0.0 && (!cross || (parsed(received) ?: 0.0) > 0.0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.movements.ruleTitle) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    listOfNotNull(s.transactionKindName(rule.kind), accountsLine(rule.accountId, rule.transferAccountId, accounts))
                        .joinToString(" · "),
                    style = MaterialTheme.typography.titleSmall,
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = amountInput(it) },
                    label = { Text(s.amountLabel + " (" + rule.currency.name.uppercase() + ")") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (cross) {
                    OutlinedTextField(
                        value = received,
                        onValueChange = { received = amountInput(it) },
                        label = { Text(s.receivedAmount + " (" + destination!!.currency.name.uppercase() + ")") },
                        supportingText = { Text(s.movements.receivedEachTime) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (matching.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = categoryId == null, onClick = { categoryId = null }, label = { Text(s.noCategory) })
                        matching.forEach { category ->
                            FilterChip(
                                selected = categoryId == category.id,
                                onClick = { categoryId = category.id },
                                label = { Text((category.icon?.plus(" ") ?: "") + category.name) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(BudgetApi.NOTE_LENGTH) },
                    label = { Text(s.descriptionOptional) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(s.movements.ruleHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { confirmingDelete = true }) { Text(s.deleteWord, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(parsed(amount)!!, parsed(received).takeIf { cross }, categoryId.takeIf { !transfer }, note.trim()) },
                enabled = valid,
            ) { Text(s.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(s.movements.deleteRuleTitle) },
            text = { Text(s.movements.deleteRuleText) },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; onDelete() }) {
                    Text(s.deleteWord, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(s.cancel) } },
        )
    }
}
