package app.kadans.ui.budget

import app.kadans.ui.BigButton
import app.kadans.ui.PageHeader
import app.kadans.ui.ProgressLine
import app.kadans.ui.ChoiceChip
import app.kadans.ui.Choices
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kadans.api.model.AccountResponse
import app.kadans.api.model.BudgetTransactionKind
import app.kadans.i18n.LocalStrings
import app.kadans.i18n.StatementImportStrings
import app.kadans.statement.CsvFile
import app.kadans.statement.CsvTable
import app.kadans.statement.StatementLine
import app.kadans.statement.StatementText.DateOrder
import app.kadans.ui.Dates
import app.kadans.ui.LocalDates
import app.kadans.ui.OnBackStep
import app.kadans.ui.PickerField
import app.kadans.ui.PickerKind
import app.kadans.ui.ReadableList
import app.kadans.ui.shell.KadansIcons
import org.koin.compose.viewmodel.koinViewModel

/**
 * A bank statement brought into the budget (ARCHITECTURE → "Statements in, data out"), in three steps: the account and
 * the file; for a CSV file, what each column is; the lines to check. Back goes one step back before it leaves.
 */
@Composable
fun StatementImportScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    viewModel: StatementImportViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val s = LocalStrings.current
    val t = s.statementImport
    OnBackStep { viewModel.back() }

    Column(Modifier.fillMaxSize()) {
        PageHeader(
            title = when (state.step) {
                ImportStep.Choose -> t.title
                ImportStep.Columns -> t.columnsTitle
                ImportStep.Review -> t.reviewTitle
                ImportStep.Done -> t.doneTitle
            },
            onBack = { if (!viewModel.back()) onBack() },
        )
        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.loadFailed -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(s.errorFor(state.errorCode, state.error), color = MaterialTheme.colorScheme.error)
                BigButton(s.retry, viewModel::load, Modifier.padding(top = 12.dp))
            }
            else -> when (state.step) {
                ImportStep.Choose -> ChooseStep(state, viewModel)
                ImportStep.Columns -> ColumnsStep(state, viewModel)
                ImportStep.Review -> ReviewStep(state, viewModel)
                ImportStep.Done -> DoneStep(state, onDone)
            }
        }
    }
}

/** A form's page: scrolls, and keeps its lines readable on a wide window. */
@Composable
private fun Page(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

private fun accountLabel(account: AccountResponse) = account.name + " · " + account.currency.name.uppercase()

@Composable
private fun ChooseStep(state: StatementImportUiState, viewModel: StatementImportViewModel) {
    val s = LocalStrings.current
    val t = s.statementImport
    Page {
        Text(t.intro, style = MaterialTheme.typography.bodyMedium)
        Text(t.stays, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.accounts.isEmpty()) {
            Text(t.noAccount, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            return@Page
        }
        Text(t.intoAccount, style = MaterialTheme.typography.labelLarge)
        Choices {
            state.accounts.forEach { account ->
                ChoiceChip(
                    selected = state.accountId == account.id,
                    onClick = { viewModel.selectAccount(account.id) },
                    label = { Text(accountLabel(account)) },
                )
            }
        }
        state.problem?.let { problem ->
            if (state.fileName.isNotBlank()) Text(state.fileName, style = MaterialTheme.typography.labelLarge)
            Text(
                when (problem) {
                    ImportProblem.TooLarge -> t.tooLarge
                    ImportProblem.Pdf -> t.pdf
                    ImportProblem.Spreadsheet -> t.spreadsheet
                    ImportProblem.Unreadable -> t.unreadable
                    ImportProblem.NoLines -> t.noLines
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = { viewModel.choose(t.fileDialogTitle) },
            enabled = state.accountId != null && !state.isReading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isReading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) else Text(t.chooseFile)
        }
    }
}

/** A column as the person reads it: its header, or its rank in a file without one. Another's text: plain, and cut. */
internal fun columnName(table: CsvTable, index: Int, t: StatementImportStrings): String =
    table.header?.getOrNull(index)?.filter { !it.isISOControl() }?.trim()?.takeIf { it.isNotEmpty() }?.take(COLUMN_SHOWN) ?: t.column(index + 1)

/** What the column holds, by its first cell that holds anything. */
internal fun columnSample(table: CsvTable, index: Int): String =
    table.rows.asSequence().take(20).map { it.getOrElse(index) { "" } }.firstOrNull { it.isNotBlank() }
        ?.filter { !it.isISOControl() }?.take(COLUMN_SHOWN).orEmpty()

private const val COLUMN_SHOWN = 40

@Composable
private fun ColumnsStep(state: StatementImportUiState, viewModel: StatementImportViewModel) {
    val s = LocalStrings.current
    val t = s.statementImport
    val table = state.table ?: return
    val mapping = state.mapping
    // One column with its sign, or one for money out and one for money in: the person can say before choosing them.
    var twoColumns by remember(table) { mutableStateOf(mapping.amount == null && (mapping.moneyOut != null || mapping.moneyIn != null)) }

    Page {
        if (state.fileName.isNotBlank()) Text(state.fileName, style = MaterialTheme.typography.labelLarge)
        Text(t.columnsHint, style = MaterialTheme.typography.bodyMedium)

        ColumnChoice(t.dateColumn, mapping.date, table, optional = false) { picked -> viewModel.map { it.copy(date = picked) } }
        if (state.datesNeedOrder) {
            Text(t.datesWritten, style = MaterialTheme.typography.labelLarge)
            Choices {
                listOf(DateOrder.DayMonthYear to t.dayMonthYear, DateOrder.MonthDayYear to t.monthDayYear, DateOrder.YearMonthDay to t.yearMonthDay).forEach { (order, label) ->
                    ChoiceChip(selected = mapping.order == order, onClick = { viewModel.map { it.copy(order = order) } }, label = { Text(label) })
                }
            }
            if (state.orderUnsure) Text(t.orderUnsure, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        ColumnChoice(t.textColumn, mapping.text, table, optional = true) { picked -> viewModel.map { it.copy(text = picked) } }

        Text(t.amountsIn, style = MaterialTheme.typography.labelLarge)
        Choices {
            ChoiceChip(
                selected = !twoColumns,
                onClick = { twoColumns = false; viewModel.map { it.copy(moneyOut = null, moneyIn = null) } },
                label = { Text(t.oneColumn) },
            )
            ChoiceChip(
                selected = twoColumns,
                onClick = { twoColumns = true; viewModel.map { it.copy(amount = null) } },
                label = { Text(t.twoColumns) },
            )
        }
        if (twoColumns) {
            ColumnChoice(t.moneyOutColumn, mapping.moneyOut, table, optional = true) { picked -> viewModel.map { it.copy(moneyOut = picked) } }
            ColumnChoice(t.moneyInColumn, mapping.moneyIn, table, optional = true) { picked -> viewModel.map { it.copy(moneyIn = picked) } }
        } else {
            ColumnChoice(t.amountColumn, mapping.amount, table, optional = false) { picked -> viewModel.map { it.copy(amount = picked) } }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.positiveIsExpense, style = MaterialTheme.typography.bodyMedium)
                    Text(t.positiveIsExpenseHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = !mapping.positiveIsIncome, onCheckedChange = { expenses -> viewModel.map { it.copy(positiveIsIncome = !expenses) } })
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text(t.previewTitle, style = MaterialTheme.typography.titleSmall)
        val read = state.read
        if (read == null || read.lines.isEmpty()) {
            Text(t.nothingRead, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        } else {
            read.lines.take(CsvFile.PREVIEW).forEach { line -> PreviewLine(line, state.account) }
            Text(
                listOfNotNull(t.linesRead(read.lines.size), read.unread.takeIf { it > 0 }?.let(t::linesNotRead)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (read.truncated) Text(t.tooManyLines, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Button(onClick = viewModel::review, enabled = state.canContinue, modifier = Modifier.fillMaxWidth()) { Text(t.continueWord) }
    }
}

/** One of the file's columns chosen from a list, each with what it holds; [optional]: "None" is a choice too. */
@Composable
private fun ColumnChoice(label: String, chosen: Int?, table: CsvTable, optional: Boolean, onChoose: (Int?) -> Unit) {
    val t = LocalStrings.current.statementImport
    var open by remember { mutableStateOf(false) }
    Box {
        PickerField(
            value = chosen?.let { columnName(table, it, t) } ?: if (optional) t.noColumn else "",
            label = label,
            kind = PickerKind.List,
            onPick = { open = true },
            isError = !optional && chosen == null,
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (optional) {
                DropdownMenuItem(text = { Text(t.noColumn) }, onClick = { open = false; onChoose(null) })
            }
            for (index in 0 until table.columns) {
                val sample = columnSample(table, index)
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(columnName(table, index, t), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (sample.isNotEmpty()) {
                                Text(sample, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    },
                    onClick = { open = false; onChoose(index) },
                )
            }
        }
    }
}

/** An amount with its way: "+ 85 000.00 HTG" came in, "− 1 250.00 HTG" went out. */
private fun signed(cents: Long, account: AccountResponse?): String {
    val amount = kotlin.math.abs(cents) / 100.0
    return (if (cents > 0) "+ " else "− ") + (account?.let { formatMoney(amount, it.currency) } ?: formatAmount(amount))
}

@Composable
private fun PreviewLine(line: StatementLine, account: AccountResponse?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(LocalDates.current.short(line.date, Dates.today()), style = MaterialTheme.typography.bodyMedium)
            if (line.text.isNotBlank()) {
                Text(line.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(signed(line.cents, account), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
}

@Composable
private fun ReviewStep(state: StatementImportUiState, viewModel: StatementImportViewModel) {
    val s = LocalStrings.current
    val t = s.statementImport
    val account = state.account
    var categoryFor by remember { mutableStateOf<String?>(null) }
    val busy = state.isChecking || state.isImporting

    Column(Modifier.fillMaxSize()) {
        ReadableList(Modifier.weight(1f).fillMaxWidth()) { listPadding ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item("head") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        account?.let { Text(t.into(accountLabel(it)), style = MaterialTheme.typography.titleMedium) }
                        if (state.fileName.isNotBlank()) {
                            Text(state.fileName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.currencyDiffers && account != null) {
                            Text(
                                t.currencyDiffers(state.statementCurrency.orEmpty(), account.currency.name.uppercase()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        when {
                            state.isChecking -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(t.checking, style = MaterialTheme.typography.bodySmall)
                            }
                            state.checkFailed -> {
                                Text(s.errorFor(state.errorCode, state.error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = viewModel::check) { Text(s.retry) }
                            }
                            state.alreadyThere == state.lines.size -> Text(t.allAlready, style = MaterialTheme.typography.bodyMedium)
                            state.alreadyThere > 0 -> Text(t.already(state.alreadyThere), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.unread > 0) {
                            Text(t.linesNotRead(state.unread), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.truncated) {
                            Text(t.tooManyLines, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        if (state.otherAccounts) {
                            Text(t.otherAccounts, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        if (!state.isChecking && !state.checkFailed && state.alreadyThere < state.lines.size) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { viewModel.keepAll(true) }, enabled = !busy) { Text(t.selectAll) }
                                TextButton(onClick = { viewModel.keepAll(false) }, enabled = !busy) { Text(t.selectNone) }
                            }
                        }
                        HorizontalDivider()
                    }
                }
                items(state.lines, key = { it.key }) { line ->
                    ReviewRow(
                        line = line,
                        account = account,
                        category = state.categories.firstOrNull { it.id == line.categoryId }?.let { (it.icon?.plus(" ") ?: "") + it.name },
                        enabled = !busy && !state.checkFailed,
                        onKeep = { viewModel.keep(line.key, it) },
                        onCategory = if (state.categoriesFor(line.kind).isEmpty()) null else ({ categoryFor = line.key }),
                    )
                }
            }
        }
        // What the button will do stays in sight, however long the list.
        HorizontalDivider()
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!state.checkFailed && (state.error != null || state.errorCode != null)) {
                Text(s.errorFor(state.errorCode, state.error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (state.isImporting) {
                Text(t.importing(state.sent, state.toSend), style = MaterialTheme.typography.bodySmall)
                ProgressLine(
                    progress = { if (state.toSend == 0) 0f else state.sent.toFloat() / state.toSend },
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                )
            }
            Button(onClick = viewModel::import, enabled = state.canImport, modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
                Text(t.import(state.kept))
            }
        }
    }

    categoryFor?.let { key ->
        val line = state.lines.firstOrNull { it.key == key }
        if (line == null) {
            categoryFor = null
        } else {
            CategoryDialog(
                line = line,
                state = state,
                others = remember(key, state.lines) { viewModel.sameAs(key) },
                onSet = { categoryId, sameToo -> viewModel.categorize(key, categoryId, sameToo); categoryFor = null },
                onDismiss = { categoryFor = null },
            )
        }
    }
}

/** A line of the statement: kept or not, what it is, and its category, which a tap changes. */
@Composable
private fun ReviewRow(
    line: ReviewLine,
    account: AccountResponse?,
    category: String?,
    enabled: Boolean,
    onKeep: (Boolean) -> Unit,
    onCategory: (() -> Unit)?,
) {
    val s = LocalStrings.current
    val t = s.statementImport
    val open = onCategory?.takeIf { enabled && !line.already }
    Row(
        Modifier.fillMaxWidth().alpha(if (line.already) 0.55f else 1f),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = line.keep && !line.already, onCheckedChange = onKeep, enabled = enabled && !line.already)
        Row(
            Modifier.weight(1f).let { if (open != null) it.clickable(role = Role.Button, onClick = open) else it }.padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    line.line.text.ifBlank { s.transactionKindName(line.kind) },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    LocalDates.current.short(line.line.date, Dates.today()) + " · " + when {
                        line.already -> t.alreadyThere
                        else -> category ?: s.noCategory
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                signed(line.line.cents, account),
                style = MaterialTheme.typography.bodyMedium,
                color = if (line.kind == BudgetTransactionKind.Income) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (open != null) Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A line's category, and by default that of the other lines that read the same: the same shop, the same salary. */
@Composable
private fun CategoryDialog(
    line: ReviewLine,
    state: StatementImportUiState,
    others: Int,
    onSet: (categoryId: String?, sameToo: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    val t = s.statementImport
    var chosen by remember(line.key) { mutableStateOf(line.categoryId) }
    var sameToo by remember(line.key) { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(line.line.text.ifBlank { s.categoryLabel }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.categoryLabel, style = MaterialTheme.typography.labelLarge)
                Choices {
                    ChoiceChip(selected = chosen == null, onClick = { chosen = null }, onCard = true, label = { Text(s.noCategory) })
                    state.categoriesFor(line.kind).forEach { category ->
                        ChoiceChip(
                            onCard = true,
                            selected = chosen == category.id,
                            onClick = { chosen = category.id },
                            label = { Text((category.icon?.plus(" ") ?: "") + category.name) },
                        )
                    }
                }
                if (others > 0) {
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { sameToo = !sameToo },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = sameToo, onCheckedChange = null, modifier = Modifier.padding(end = 10.dp, top = 8.dp, bottom = 8.dp))
                        Text(t.sameToo(others), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSet(chosen, sameToo && others > 0) }) { Text(s.ok) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}

@Composable
private fun DoneStep(state: StatementImportUiState, onDone: () -> Unit) {
    val t = LocalStrings.current.statementImport
    Page {
        Text(t.imported(state.imported, state.account?.name.orEmpty()), style = MaterialTheme.typography.titleMedium)
        if (state.skipped > 0 && state.imported > 0) {
            Text(t.skipped(state.skipped), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(t.done) }
    }
}
