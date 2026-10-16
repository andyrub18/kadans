package app.kadans.ui.budget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.kadans.api.BudgetApi
import app.kadans.api.model.AccountResponse
import app.kadans.api.model.BudgetTransactionKind
import app.kadans.i18n.LocalStrings
import app.kadans.ui.DateRange
import app.kadans.ui.PickerField
import app.kadans.ui.PickerKind
import app.kadans.ui.todos.RepeatSection
import app.kadans.ui.todos.RuleSummary
import kotlinx.datetime.TimeZone
import kotlinx.datetime.LocalDate
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** A new movement, or with [transactionId] one of the month's to correct. */
@Composable
fun BudgetAddScreen(
    onSaved: () -> Unit,
    onBack: () -> Unit,
    transactionId: String? = null,
    viewModel: BudgetAddViewModel =
        koinViewModel(key = transactionId?.let { "budget-edit-$it" }) { parametersOf(transactionId) },
) {
    val state by viewModel.state.collectAsState()
    val s = LocalStrings.current
    var pickingDate by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) { viewModel.saved.collect { onSaved() } }
    // Accounts or categories created since the last open must show up.
    LaunchedEffect(Unit) { viewModel.reload() }

    if (state.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (state.loadFailed) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(s.errorFor(state.errorCode, state.error), color = MaterialTheme.colorScheme.error)
            Button(onClick = viewModel::reload, modifier = Modifier.padding(top = 12.dp)) { Text(s.retry) }
            TextButton(onClick = onBack) { Text(s.cancel) }
        }
        return
    }
    /** An account as its chip reads; one archived since shows only on its own old movement, and says so. */
    fun accountLabel(account: AccountResponse) =
        account.name + " · " + account.currency.name.uppercase() + (if (account.isArchived) " · " + s.movements.archived else "")

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (state.editing) s.movements.editTitle else s.addTransaction, style = MaterialTheme.typography.headlineSmall)

            if (state.editing) {
                // Its kind stays: an income recorded as an expense is deleted and recorded again.
                Text(s.transactionKindName(state.kind), style = MaterialTheme.typography.titleMedium)
                if (state.bookedByRule) {
                    Text(s.movements.bookedByRule, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BudgetTransactionKind.entries.forEach { kind ->
                        FilterChip(
                            selected = state.kind == kind,
                            onClick = { viewModel.update { it.copy(kind = kind, categoryId = null) } },
                            label = { Text(s.transactionKindName(kind)) },
                        )
                    }
                }
            }

            Text(s.fromAccount, style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.accounts.forEach { account ->
                    FilterChip(
                        selected = state.accountId == account.id,
                        onClick = { viewModel.update { it.copy(accountId = account.id) } },
                        label = { Text(accountLabel(account)) },
                    )
                }
            }

            if (state.kind == BudgetTransactionKind.Transfer) {
                Text(s.toAccount, style = MaterialTheme.typography.labelLarge)
                val destinations = state.accounts.filter { it.id != state.accountId }
                if (destinations.isEmpty()) {
                    Text(s.transferNeedsTwoAccounts, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        destinations.forEach { account ->
                            FilterChip(
                                selected = state.transferAccountId == account.id,
                                onClick = { viewModel.update { it.copy(transferAccountId = account.id) } },
                                label = { Text(accountLabel(account)) },
                            )
                        }
                    }
                }
            }

            // Two decimals and below a trillion, as the server takes them: the field keeps no more.
            OutlinedTextField(
                value = state.amountText,
                onValueChange = { v -> viewModel.update { it.copy(amountText = amountInput(v)) } },
                label = { Text(s.amountLabel + (state.account?.let { " (" + it.currency.name.uppercase() + ")" } ?: "")) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.crossCurrency) {
                OutlinedTextField(
                    value = state.receivedText,
                    onValueChange = { v -> viewModel.update { it.copy(receivedText = amountInput(v)) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    label = { Text(s.receivedAmount + (state.transferAccount?.let { " (" + it.currency.name.uppercase() + ")" } ?: "")) },
                    singleLine = true,
                    placeholder = { state.suggestedReceived()?.let { Text(formatAmount(it)) } },
                    // A rule books the same pair each time: the day's real exchange is corrected on its movement.
                    supportingText = if (state.repeating) ({ Text(s.movements.receivedEachTime) }) else null,
                    trailingIcon = {
                        state.suggestedReceived()?.let { suggested ->
                            TextButton(onClick = { viewModel.update { it.copy(receivedText = editableAmount(suggested)) } }) {
                                Text(s.atYourRate, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (state.matchingCategories.isNotEmpty()) {
                Text(s.categoryLabel, style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = state.categoryId == null,
                        onClick = { viewModel.update { it.copy(categoryId = null) } },
                        label = { Text(s.noCategory) },
                    )
                    state.matchingCategories.forEach { category ->
                        FilterChip(
                            selected = state.categoryId == category.id,
                            onClick = { viewModel.update { it.copy(categoryId = category.id) } },
                            label = { Text((category.icon?.plus(" ") ?: "") + category.name) },
                        )
                    }
                }
            }

            PickerField(
                value = state.date?.toString() ?: "",
                label = if (state.repeating) s.firstOn else s.dueDate,
                kind = PickerKind.Date,
                onPick = { pickingDate = true },
                isError = state.startTooOld,
                supportingText = if (state.startTooOld) s.repeat.startWithinAYear else null,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.note,
                onValueChange = { v -> viewModel.update { it.copy(note = v.take(BudgetApi.NOTE_LENGTH)) } },
                label = { Text(s.descriptionOptional) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (!state.editing) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(s.repeatLabel)
                    Switch(checked = state.repeat, onCheckedChange = { v -> viewModel.update { it.copy(repeat = v) } })
                }
                if (state.repeat) {
                    // The todo's rule builder: "the 15th and the 30th", "the last Friday", some months, an end.
                    RepeatSection(
                        state = state.rule,
                        update = { transform -> viewModel.update { it.copy(repeatForm = transform(it.rule)) } },
                        frequencies = BudgetAddViewModel.FREQUENCIES,
                        severalTimes = false,
                        endTime = false,
                    )
                    val zone = remember { TimeZone.currentSystemDefault() }
                    BudgetAddViewModel.ruleInWords(state, s, zone)?.let { words ->
                        Text(words, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    val first = state.firstDate
                    if (first != null && first != state.date) {
                        Text(
                            s.repeat.firstTime(RuleSummary.day(first, s)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.rule.neverFalls) {
                        Text(s.repeat.neverFalls, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (state.error != null || state.errorCode != null) {
                Text(s.errorFor(state.errorCode, state.error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = viewModel::submit, enabled = state.canSubmit, modifier = Modifier.fillMaxWidth()) {
                if (state.isSaving) CircularProgressIndicator(modifier = Modifier.padding(2.dp))
                else Text(if (state.editing) s.save else s.create)
            }
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(s.cancel) }
            if (state.editing) {
                TextButton(onClick = { confirmingDelete = true }, enabled = !state.isSaving, modifier = Modifier.fillMaxWidth()) {
                    Text(s.deleteWord, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(s.movements.deleteTitle) },
            text = { Text(s.movements.deleteText) },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; viewModel.delete() }) {
                    Text(s.deleteWord, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(s.cancel) } },
        )
    }

    if (pickingDate) {
        // Only days the server accepts: a one-off movement can be on any day, a repeating one starts at most a year ago.
        val range = if (state.repeating) DateRange(state.earliestStart, null) else DateRange(null, null)
        val pickerState = rememberDatePickerState(selectableDates = range)
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val picked = LocalDate.fromEpochDays((millis / 86_400_000L).toInt())
                        viewModel.update { it.copy(date = picked) }
                    }
                    pickingDate = false
                }) { Text(s.ok) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(s.cancel) } },
        ) { DatePicker(state = pickerState) }
    }
}

