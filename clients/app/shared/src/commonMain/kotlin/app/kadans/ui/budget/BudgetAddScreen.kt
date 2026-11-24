package app.kadans.ui.budget

import app.kadans.ui.BigButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kadans.api.BudgetApi
import app.kadans.api.model.AccountResponse
import app.kadans.api.model.BudgetTransactionKind
import app.kadans.i18n.LocalStrings
import app.kadans.ui.ChoiceChip
import app.kadans.ui.Choices
import app.kadans.ui.DateRange
import app.kadans.ui.FieldGroup
import app.kadans.ui.FieldLabel
import app.kadans.ui.FormActions
import app.kadans.ui.FormColumns
import app.kadans.ui.FormPage
import app.kadans.ui.KadansCard
import app.kadans.ui.KadansField
import app.kadans.ui.PickerField
import app.kadans.ui.PickerKind
import app.kadans.ui.RuleWords
import app.kadans.ui.Segmented
import app.kadans.ui.SwitchCard
import app.kadans.ui.todos.RepeatSection
import app.kadans.ui.Dates
import app.kadans.ui.LocalDates
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
            BigButton(s.retry, viewModel::reload, Modifier.padding(top = 12.dp))
            TextButton(onClick = onBack) { Text(s.cancel) }
        }
        return
    }
    /** An account as its chip reads; one archived since shows only on its own old movement, and says so. */
    fun accountLabel(account: AccountResponse) =
        account.name + " · " + account.currency.name.uppercase() + (if (account.isArchived) " · " + s.movements.archived else "")

    FormPage(if (state.editing) s.movements.editTitle else s.addTransaction, onBack) { wide ->
        // What it is and how much, the accounts and the category, the day and a note, whether it repeats: a phone reads
        // them in that order, a wide window puts the choices and the rule on the right.
        val amount: @Composable ColumnScope.() -> Unit = {
            if (state.editing) {
                // Its kind stays: an income recorded as an expense is deleted and recorded again.
                Text(s.transactionKindName(state.kind), style = MaterialTheme.typography.titleMedium)
                if (state.bookedByRule) {
                    Text(s.movements.bookedByRule, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Segmented(
                    options = BudgetTransactionKind.entries,
                    selected = state.kind,
                    name = { s.transactionKindName(it) },
                    onSelect = { kind -> viewModel.update { it.copy(kind = kind, categoryId = null) } },
                )
            }

            // Two decimals and below a trillion, as the server takes them: the field keeps no more.
            AmountCard(
                text = state.amountText,
                onChange = { v -> viewModel.update { it.copy(amountText = amountInput(v)) } },
                label = s.amountLabel,
                currency = state.account?.currency?.name?.uppercase(),
            )

            if (state.crossCurrency) {
                KadansField(
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
        }
        val choices: @Composable ColumnScope.() -> Unit = {
            FieldGroup(s.fromAccount) {
                Choices {
                    state.accounts.forEach { account ->
                        ChoiceChip(
                            selected = state.accountId == account.id,
                            onClick = { viewModel.update { it.copy(accountId = account.id) } },
                            label = { Text(accountLabel(account)) },
                        )
                    }
                }
            }

            if (state.kind == BudgetTransactionKind.Transfer) {
                FieldGroup(s.toAccount) {
                    val destinations = state.accounts.filter { it.id != state.accountId }
                    if (destinations.isEmpty()) {
                        Text(s.transferNeedsTwoAccounts, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    } else {
                        Choices {
                            destinations.forEach { account ->
                                ChoiceChip(
                                    selected = state.transferAccountId == account.id,
                                    onClick = { viewModel.update { it.copy(transferAccountId = account.id) } },
                                    label = { Text(accountLabel(account)) },
                                )
                            }
                        }
                    }
                }
            }

            if (state.matchingCategories.isNotEmpty()) {
                FieldGroup(s.categoryLabel) {
                    Choices {
                        ChoiceChip(
                            selected = state.categoryId == null,
                            onClick = { viewModel.update { it.copy(categoryId = null) } },
                            label = { Text(s.noCategory) },
                        )
                        state.matchingCategories.forEach { category ->
                            ChoiceChip(
                                selected = state.categoryId == category.id,
                                onClick = { viewModel.update { it.copy(categoryId = category.id) } },
                                label = { Text((category.icon?.plus(" ") ?: "") + category.name) },
                            )
                        }
                    }
                }
            }
        }
        val day: @Composable ColumnScope.() -> Unit = {
            PickerField(
                value = state.date?.let { LocalDates.current.short(it, Dates.today()) } ?: "",
                label = if (state.repeating) s.firstOn else s.dueDate,
                kind = PickerKind.Date,
                onPick = { pickingDate = true },
                isError = state.startTooOld,
                supportingText = if (state.startTooOld) s.repeat.startWithinAYear else null,
                modifier = Modifier.fillMaxWidth(),
            )

            KadansField(
                value = state.note,
                onValueChange = { v -> viewModel.update { it.copy(note = v.take(BudgetApi.NOTE_LENGTH)) } },
                label = { Text(s.descriptionOptional) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val repeat: @Composable ColumnScope.() -> Unit = {
            if (!state.editing) {
                SwitchCard(s.repeatLabel, state.repeat, onChange = { v -> viewModel.update { it.copy(repeat = v) } })
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
                    BudgetAddViewModel.ruleInWords(state, s, zone)?.let { words -> RuleWords(words) }
                    val first = state.firstDate
                    if (first != null && first != state.date) {
                        Text(
                            s.repeat.firstTime(LocalDates.current.shortDay(first, Dates.today(zone))),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.rule.neverFalls) {
                        Text(s.repeat.neverFalls, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (wide) {
            FormColumns(first = { amount(); day() }, second = { choices(); repeat() })
        } else {
            amount()
            choices()
            day()
            repeat()
        }

        if (state.error != null || state.errorCode != null) {
            Text(s.errorFor(state.errorCode, state.error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        FormActions(
            wide = wide,
            confirm = if (state.editing) s.save else s.create,
            onConfirm = viewModel::submit,
            onCancel = onBack,
            enabled = state.canSubmit,
            busy = state.isSaving,
        )
        if (state.editing) {
            TextButton(
                onClick = { confirmingDelete = true },
                enabled = !state.isSaving,
                modifier = Modifier.align(if (wide) Alignment.End else Alignment.CenterHorizontally),
            ) {
                Text(s.deleteWord, color = MaterialTheme.colorScheme.error)
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


/**
 * The amount, the form's largest thing: written big on a card of its own, its currency beside it. The digits get
 * smaller as they get many, so that a large amount is still read whole on a phone.
 */
@Composable
private fun AmountCard(text: String, onChange: (String) -> Unit, label: String, currency: String?) {
    val scheme = MaterialTheme.colorScheme
    val size = when {
        text.length <= 8 -> 44.sp
        text.length <= 11 -> 34.sp
        else -> 26.sp
    }
    // The line is as tall as it says, the digits in its middle: Baloo's own line would make the cursor tower over them.
    val digits = MaterialTheme.typography.displayMedium.copy(
        fontSize = size,
        lineHeight = 52.sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        color = scheme.onSurface,
    )
    KadansCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), padding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 10.dp)) {
        FieldLabel(label)
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = text,
                onValueChange = onChange,
                singleLine = true,
                textStyle = digits,
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f).semantics { contentDescription = label + (currency?.let { " ($it)" } ?: "") },
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) Text("0", style = digits, color = scheme.outline)
                        field()
                    }
                },
            )
            if (currency != null) {
                Text(currency, style = MaterialTheme.typography.titleLarge, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
