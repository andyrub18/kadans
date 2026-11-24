package app.kadans.ui.budget

import app.kadans.ui.BigButton
import app.kadans.ui.ChoiceChip
import app.kadans.ui.Choices
import app.kadans.ui.KadansField
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import app.kadans.ui.KadansCard
import app.kadans.ui.LocalKadansColors
import app.kadans.ui.SectionHeader
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import app.kadans.ui.Dates
import app.kadans.ui.FabClearance
import app.kadans.ui.ReadableList
import app.kadans.ui.LocalDates
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import app.kadans.files.FilePorts
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import app.kadans.ui.shell.KadansIcons

@Composable
fun BudgetScreen(
    onEditTransaction: (String) -> Unit,
    onImport: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: BudgetViewModel = koinViewModel(),
    // A statement comes in through the system's file picker: where the device has none, it is not offered.
    canImport: Boolean = koinInject<FilePorts>().available,
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

    // The destination's name, the bell and + are the frame's (MainShell); the month and its arrows lead the screen.
    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        topBar = {
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                // Over the list: its readable width on a phone, its two columns' left edge in a wide window.
                val row = if (maxWidth >= TwoColumns) Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp)
                else Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(start = 16.dp, end = 8.dp)
                Row(row, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (s.monthNames[state.month - 1] + " " + state.year).replaceFirstChar { it.titlecase() },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = viewModel::previousMonth) { Icon(KadansIcons.Previous, contentDescription = s.nav.previousMonth) }
                    IconButton(onClick = viewModel::nextMonth) { Icon(KadansIcons.Next, contentDescription = s.nav.nextMonth) }
                }
            }
        },
    ) { padding ->
        val summary = state.summary
        when {
            state.isLoading && summary == null ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.error != null || state.errorCode != null ->
                Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(s.errorFor(state.errorCode, state.error), color = MaterialTheme.colorScheme.error)
                    BigButton(s.retry, viewModel::refresh, Modifier.padding(top = 12.dp))
                }
            summary != null -> {
                // The screen's parts, one under the other on a phone and in two columns in a wide window.
                val overview: LazyListScope.() -> Unit = {
                    if (state.actionError != null || state.actionErrorCode != null) {
                        item(key = "error") { Text(s.errorFor(state.actionErrorCode, state.actionError), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                    // What there is in all, the month's money in and out: everything together at the person's rates
                    // once a second currency exists, else the one currency's own figures.
                    val currencies = summary.accounts.map { it.currency }.distinct()
                    val combined = summary.combined.takeIf { currencies.size > 1 }
                    if (combined != null) {
                        item(key = "balance") {
                            BalanceCard(
                                note = s.atYourRate,
                                balance = formatMoney(combined.totalBalance, combined.baseCurrency),
                                income = combined.income,
                                expense = combined.expense,
                                net = combined.net,
                                warning = combined.missingRates.takeIf { it.isNotEmpty() }
                                    ?.let { missing -> s.missingRatesLabel + " " + missing.joinToString { it.name.uppercase() } },
                            )
                        }
                        // Each currency's own figures, exact, under the estimate.
                        items(summary.totals, key = { "tot-" + it.currency.name }) { totals ->
                            Text(
                                "${totals.currency.name.uppercase()} — ${s.income}: ${formatAmount(totals.income)} · ${s.expense}: ${formatAmount(totals.expense)} · ${s.netLabel}: ${formatAmount(totals.net)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        currencies.singleOrNull()?.let { currency ->
                            val totals = summary.totals.firstOrNull { it.currency == currency }
                            item(key = "balance") {
                                BalanceCard(
                                    note = null,
                                    balance = formatMoney(summary.accounts.sumOf { it.balance }, currency),
                                    income = totals?.income ?: 0.0,
                                    expense = totals?.expense ?: 0.0,
                                    net = totals?.net ?: 0.0,
                                    warning = null,
                                )
                            }
                        }
                    }
                    item(key = "rates") { TextButton(onClick = { editingRate = true }) { Text(s.exchangeRateTitle) } }
                }
                val accounts: LazyListScope.() -> Unit = {
                    item(key = "accounts") { SectionHeader(s.accountsSection, action = s.newAccount, onAction = { creatingAccount = true }) }
                    item(key = "account tiles") {
                        // As many tiles side by side as fit, two at least: every account in sight without a swipe.
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val columns = maxOf(2, (maxWidth / 176.dp).toInt())
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                summary.accounts.chunked(columns).forEach { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        row.forEach { account -> AccountTile(account, onClick = { editingAccount = account }, Modifier.weight(1f)) }
                                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                        }
                    }
                }
                val categories: LazyListScope.() -> Unit = {
                    // ---- categories: the month's spending, and each limit in its period ----
                    item(key = "categories") { SectionHeader(s.categoriesSection, action = s.newCategory, onAction = { creatingCategory = true }) }
                    // Every expense category (a limit can be set before anything is spent), then the incomes of the month.
                    val shownCategories = state.categories.filter { !it.isArchived }
                        .filter { c -> c.kind == CategoryKind.Expense || summary.categories.any { it.categoryId == c.id } }
                        .sortedWith(compareBy({ it.kind != CategoryKind.Expense }, { it.name.lowercase() }))
                    if (shownCategories.isNotEmpty()) {
                        item(key = "category rows") {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(vertical = 6.dp)) {
                                    shownCategories.forEach { category ->
                                        CategoryRow(
                                            category = category,
                                            spends = summary.categories.filter { it.categoryId == category.id },
                                            limit = summary.limits.firstOrNull { it.categoryId == category.id },
                                            // A weekly limit is this week's: on another month there is no bar to show.
                                            showsWeek = state.year == today.year && state.month == today.month.number,
                                            onClick = if (category.kind == CategoryKind.Expense) ({ limitFor = category }) else null,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                val recurring: LazyListScope.() -> Unit = {
                    if (state.recurring.isNotEmpty()) {
                        item(key = "recurring") { SectionHeader(s.recurringSection) }
                        items(state.recurring, key = { "rec-" + it.id }) { rule ->
                            Card(onClick = { editingRule = rule }, modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                        Text(
                                            "${s.transactionKindName(rule.kind)} · ${formatMoney(rule.amount, rule.currency)}" +
                                                received(rule.kind, rule.currency, rule.transferAccountId, rule.transferAmount, state.accounts) +
                                                (rule.note.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        // How it repeats, as the form said it ("Every month · on days 15, 30"), then the next one.
                                        Text(
                                            RuleSummary.describe(rule.asRule(), s, TimeZone.currentSystemDefault(), withTime = false, clock = LocalDates.current.clock),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        Text(
                                            listOfNotNull(
                                                rule.nextAt?.toLocalDateTime(TimeZone.currentSystemDefault())?.date?.let {
                                                    s.movements.next(LocalDates.current.shortDay(it, Dates.today()))
                                                },
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
                    }
                }
                val movements: LazyListScope.() -> Unit = {
                    // ---- the month's movements: one opens to correct it ----
                    item(key = "movements") {
                        SectionHeader(s.movements.listTitle, action = s.statementImport.title.takeIf { canImport }, onAction = onImport)
                    }
                    if (state.recent.isEmpty()) {
                        item(key = "no movements") { Text(s.noTransactionsYet, style = MaterialTheme.typography.bodyMedium) }
                    }
                    items(state.recent, key = { "tx-" + it.id }) { tx ->
                        MovementRow(tx, state.accounts, state.categories, s, onClick = { onEditTransaction(tx.id) })
                    }
                    if (state.recentHasMore) {
                        item(key = "more") {
                            TextButton(onClick = viewModel::showMore, modifier = Modifier.fillMaxWidth()) { Text(s.movements.showMore) }
                        }
                    }
                }

                BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                    if (maxWidth >= TwoColumns) {
                        // A computer: what there is on the left, where it goes on the right, each scrolling by itself.
                        Row(Modifier.fillMaxSize().padding(start = 8.dp, end = 28.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                overview()
                                accounts()
                                recurring()
                            }
                            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                categories()
                                movements()
                            }
                        }
                    } else {
                        // Centred, and clear of the floating + at the bottom (MainShell).
                        ReadableList(Modifier.fillMaxSize(), bottom = FabClearance) { listPadding ->
                            LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                overview()
                                accounts()
                                categories()
                                recurring()
                                movements()
                            }
                        }
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
                KadansField(value = name, onValueChange = { name = it }, label = { Text(s.accountName) }, singleLine = true)
                Choices {
                    Currency.entries.forEach { c ->
                        ChoiceChip(selected = currency == c, onClick = { currency = c }, onCard = true, label = { Text(c.name.uppercase()) })
                    }
                }
                Choices {
                    AccountType.entries.forEach { t ->
                        ChoiceChip(selected = type == t, onClick = { type = t }, onCard = true, label = { Text(s.accountTypeName(t)) })
                    }
                }
                KadansField(value = balance, onValueChange = { balance = it }, label = { Text(s.initialBalanceLabel) }, singleLine = true)
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
                KadansField(value = name, onValueChange = { name = it }, label = { Text(s.accountName) }, singleLine = true)
                // The currency is the account's identity: change it by making a new account.
                Text(s.currencyLabel + ": " + account.currency.name.uppercase(), style = MaterialTheme.typography.bodySmall)
                Choices {
                    AccountType.entries.forEach { t ->
                        ChoiceChip(selected = type == t, onClick = { type = t }, onCard = true, label = { Text(s.accountTypeName(t)) })
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
                KadansField(value = name, onValueChange = { name = it }, label = { Text(s.categoryName) }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip(selected = kind == CategoryKind.Expense, onClick = { kind = CategoryKind.Expense }, onCard = true, label = { Text(s.expense) })
                    ChoiceChip(selected = kind == CategoryKind.Income, onClick = { kind = CategoryKind.Income }, onCard = true, label = { Text(s.income) })
                }
                KadansField(value = icon, onValueChange = { icon = it }, label = { Text(s.iconOptional) }, singleLine = true)
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
                Choices {
                    Currency.entries.forEach { c ->
                        ChoiceChip(selected = base == c, onClick = { onSetBase(c) }, onCard = true, label = { Text(c.name.uppercase()) })
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
                    Choices {
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
        KadansField(
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

/** A list this wide puts what there is and where it goes side by side. */
private val TwoColumns = 720.dp

/**
 * What there is in all, large on the app's purple, over the month's money in, money out and what is left of it.
 * [note]: said above the balance when it is an estimate across currencies; [warning]: a currency without a rate.
 */
@Composable
private fun BalanceCard(note: String?, balance: String, income: Double, expense: Double, net: Double, warning: String?) {
    val s = LocalStrings.current
    val k = LocalKadansColors.current
    KadansCard(
        Modifier.fillMaxWidth(),
        color = k.hero,
        contentColor = k.onHero,
        padding = PaddingValues(20.dp),
    ) {
        Text(s.totalBalanceLabel, style = MaterialTheme.typography.labelMedium, color = k.onHeroSoft)
        // One line whatever the amount: the figures shrink before any of them is cut.
        Text(
            balance,
            style = MaterialTheme.typography.headlineLarge,
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = 34.sp, stepSize = 2.sp),
        )
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = k.onHeroSoft)
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MoneyTile(s.income, income, KadansIcons.ArrowUp, MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
            MoneyTile(s.expense, expense, KadansIcons.ArrowDown, k.expense, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s.netLabel, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(formatAmount(net), style = MaterialTheme.typography.titleSmall)
        }
        if (warning != null) {
            Text(warning, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** Money in or money out of the month, on the balance card: an arrow says which, beside the word. */
@Composable
private fun MoneyTile(label: String, amount: Double, icon: ImageVector, tint: Color, modifier: Modifier = Modifier) {
    val k = LocalKadansColors.current
    Row(
        modifier.background(k.heroTile, RoundedCornerShape(18.dp)).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                formatAmount(amount),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 16.sp, stepSize = 1.sp),
            )
        }
    }
}

/** An account: its kind as a small picture, its name, what is in it. A tap opens it to rename or archive it. */
@Composable
private fun AccountTile(account: AccountResponse, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val k = LocalKadansColors.current
    val scheme = MaterialTheme.colorScheme
    val (icon, container, content) = when (account.type) {
        AccountType.Cash -> Triple(KadansIcons.Cash, k.waiting, k.onWaiting)
        AccountType.Bank -> Triple(KadansIcons.Bank, scheme.primaryContainer, scheme.onPrimaryContainer)
        AccountType.MobileMoney -> Triple(KadansIcons.Phone, scheme.tertiaryContainer, scheme.onTertiaryContainer)
        AccountType.Card -> Triple(KadansIcons.Card, k.expenseContainer, k.expense)
        AccountType.Savings -> Triple(KadansIcons.Coins, scheme.tertiaryContainer, scheme.onTertiaryContainer)
        AccountType.Other -> Triple(KadansIcons.Budget, scheme.surfaceVariant, scheme.onSurfaceVariant)
    }
    Card(onClick = onClick, modifier = modifier.semantics(mergeDescendants = true) { }) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(34.dp).background(container, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = s.accountTypeName(account.type), tint = content, modifier = Modifier.size(18.dp))
            }
            Text(account.name, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // The amount and its currency wrap apart rather than lose a figure in a narrow tile.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.Bottom) {
                Text(formatAmount(account.balance), style = MaterialTheme.typography.titleSmall)
                Text(account.currency.name.uppercase(), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
    }
}

/** A limit's bar: how much of it is used, round at both ends. */
@Composable
private fun LimitBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val track = LocalKadansColors.current.barTrack
    Box(modifier.fillMaxWidth().height(10.dp).background(track, CircleShape)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(10.dp).background(color, CircleShape))
    }
}

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
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text((category.icon?.plus(" ") ?: "") + category.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(end = 8.dp))
            Text(
                spends.sortedByDescending { it.amount }.joinToString(" · ") { formatMoney(it.amount, it.currency) }.ifEmpty { "—" },
                style = MaterialTheme.typography.labelLarge,
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
                // Green while there is room, amber from 80%, red once the limit is reached: the bar and its words.
                val k = LocalKadansColors.current
                val (bar, color) = when {
                    limit.percent >= 100 -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.error
                    limit.percent >= 80 -> k.near to k.near
                    else -> k.done to MaterialTheme.colorScheme.tertiary
                }
                LimitBar(limit.percent / 100f, bar, Modifier.padding(top = 2.dp))
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
                KadansField(
                    value = amount,
                    onValueChange = { amount = amountInput(it) },
                    label = { Text(s.amountLabel + " (" + currency.name.uppercase() + ")") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (existing == null) {
                    Choices {
                        Currency.entries.forEach { c ->
                            ChoiceChip(selected = currency == c, onClick = { currency = c }, onCard = true, label = { Text(c.name.uppercase()) })
                        }
                    }
                } else {
                    Text(s.limits.currencyFixed(currency.name.uppercase()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Choices {
                    listOf(LimitPeriod.Week to s.limits.weekly, LimitPeriod.Month to s.limits.monthly, LimitPeriod.Year to s.limits.yearly)
                        .forEach { (p, label) -> ChoiceChip(selected = period == p, onClick = { period = p }, onCard = true, label = { Text(label) }) }
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

/**
 * One movement of the month: an arrow for money in, out or across, what it was (its note, else its category), then
 * when and where, and the amount on the right. A tap opens it to correct it.
 */
@Composable
private fun MovementRow(
    tx: BudgetTransactionResponse,
    accounts: List<AccountResponse>,
    categories: List<CategoryResponse>,
    s: StringsCatalog,
    onClick: () -> Unit,
) {
    val k = LocalKadansColors.current
    val scheme = MaterialTheme.colorScheme
    val sign = when (tx.kind) {
        BudgetTransactionKind.Expense -> "− "
        BudgetTransactionKind.Income -> "+ "
        BudgetTransactionKind.Transfer -> ""
    }
    val (icon, container, content) = when (tx.kind) {
        BudgetTransactionKind.Expense -> Triple(KadansIcons.ArrowDown, k.expenseContainer, k.expense)
        BudgetTransactionKind.Income -> Triple(KadansIcons.ArrowUp, scheme.tertiaryContainer, scheme.onTertiaryContainer)
        BudgetTransactionKind.Transfer -> Triple(KadansIcons.Swap, scheme.primaryContainer, scheme.onPrimaryContainer)
    }
    val category = categories.firstOrNull { it.id == tx.categoryId }?.let { (it.icon?.plus(" ") ?: "") + it.name }
    val note = tx.note.takeIf { it.isNotBlank() }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(38.dp).background(container, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = s.transactionKindName(tx.kind), tint = content, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    note ?: category ?: s.transactionKindName(tx.kind),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        LocalDates.current.shortDay(tx.occurredAt.toLocalDateTime(TimeZone.currentSystemDefault()).date, Dates.today()),
                        accountsLine(tx.accountId, tx.transferAccountId, accounts),
                        category.takeIf { note != null },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                sign + formatMoney(tx.amount, tx.currency) + received(tx.kind, tx.currency, tx.transferAccountId, tx.transferAmount, accounts),
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.End,
            )
        }
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
                KadansField(
                    value = amount,
                    onValueChange = { amount = amountInput(it) },
                    label = { Text(s.amountLabel + " (" + rule.currency.name.uppercase() + ")") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (cross) {
                    KadansField(
                        value = received,
                        onValueChange = { received = amountInput(it) },
                        label = { Text(s.receivedAmount + " (" + destination.currency.name.uppercase() + ")") },
                        supportingText = { Text(s.movements.receivedEachTime) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (matching.isNotEmpty()) {
                    Choices {
                        ChoiceChip(selected = categoryId == null, onClick = { categoryId = null }, onCard = true, label = { Text(s.noCategory) })
                        matching.forEach { category ->
                            ChoiceChip(
                                onCard = true,
                                selected = categoryId == category.id,
                                onClick = { categoryId = category.id },
                                label = { Text((category.icon?.plus(" ") ?: "") + category.name) },
                            )
                        }
                    }
                }
                KadansField(
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
