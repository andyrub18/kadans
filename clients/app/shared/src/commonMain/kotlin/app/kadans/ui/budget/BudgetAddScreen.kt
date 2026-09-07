package app.kadans.ui.budget

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import app.kadans.api.model.BudgetTransactionKind
import app.kadans.api.model.Frequency
import app.kadans.i18n.LocalStrings
import kotlinx.datetime.LocalDate
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun BudgetAddScreen(
    onSaved: () -> Unit,
    onBack: () -> Unit,
    viewModel: BudgetAddViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val s = LocalStrings.current
    var pickingDate by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) { viewModel.saved.collect { onSaved() } }

    if (state.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(s.addTransaction, style = MaterialTheme.typography.headlineSmall)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BudgetTransactionKind.entries.forEach { kind ->
                    FilterChip(
                        selected = state.kind == kind,
                        onClick = { viewModel.update { it.copy(kind = kind, categoryId = null, repeat = it.repeat && kind != BudgetTransactionKind.Transfer) } },
                        label = { Text(s.transactionKindName(kind)) },
                    )
                }
            }

            Text(s.fromAccount, style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.accounts.forEach { account ->
                    FilterChip(
                        selected = state.accountId == account.id,
                        onClick = { viewModel.update { it.copy(accountId = account.id) } },
                        label = { Text(account.name + " · " + account.currency.name.uppercase()) },
                    )
                }
            }

            if (state.kind == BudgetTransactionKind.Transfer) {
                Text(s.toAccount, style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.accounts.filter { it.id != state.accountId }.forEach { account ->
                        FilterChip(
                            selected = state.transferAccountId == account.id,
                            onClick = { viewModel.update { it.copy(transferAccountId = account.id) } },
                            label = { Text(account.name + " · " + account.currency.name.uppercase()) },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = state.amountText,
                onValueChange = { v -> viewModel.update { it.copy(amountText = v) } },
                label = { Text(s.amountLabel + (state.account?.let { " (" + it.currency.name.uppercase() + ")" } ?: "")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.crossCurrency) {
                OutlinedTextField(
                    value = state.receivedText,
                    onValueChange = { v -> viewModel.update { it.copy(receivedText = v) } },
                    label = { Text(s.receivedAmount + (state.transferAccount?.let { " (" + it.currency.name.uppercase() + ")" } ?: "")) },
                    singleLine = true,
                    placeholder = { state.suggestedReceived()?.let { Text(formatAmount(it)) } },
                    trailingIcon = {
                        state.suggestedReceived()?.let { suggested ->
                            TextButton(onClick = { viewModel.update { it.copy(receivedText = formatAmount(suggested).replace(" ", "")) } }) {
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

            OutlinedTextField(
                value = state.date?.toString() ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text(s.dueDate) },
                trailingIcon = { TextButton(onClick = { pickingDate = true }) { Text(s.pick) } },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.note,
                onValueChange = { v -> viewModel.update { it.copy(note = v) } },
                label = { Text(s.descriptionOptional) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.kind != BudgetTransactionKind.Transfer) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(s.repeatLabel)
                    Switch(checked = state.repeat, onCheckedChange = { v -> viewModel.update { it.copy(repeat = v) } })
                }
                if (state.repeat) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(Frequency.Daily, Frequency.Weekly, Frequency.Monthly, Frequency.Yearly).forEach { f ->
                            FilterChip(
                                selected = state.frequency == f,
                                onClick = { viewModel.update { it.copy(frequency = f) } },
                                label = { Text(s.frequencyName(f)) },
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { viewModel.update { it.copy(interval = (it.interval - 1).coerceAtLeast(1)) } }) { Text("−") }
                        Text(s.every(state.frequency, state.interval), style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(onClick = { viewModel.update { it.copy(interval = it.interval + 1) } }) { Text("+") }
                    }
                    OutlinedTextField(
                        value = state.count?.toString() ?: "",
                        onValueChange = { v -> viewModel.update { it.copy(count = v.toIntOrNull()) } },
                        label = { Text(s.howManyTimes + " (" + s.endNever.lowercase() + " = ∅)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (state.error != null || state.errorCode != null) {
                Text(s.errorFor(state.errorCode, state.error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = viewModel::submit, enabled = state.canSubmit, modifier = Modifier.fillMaxWidth()) {
                if (state.isSaving) CircularProgressIndicator(modifier = Modifier.padding(2.dp)) else Text(s.create)
            }
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(s.cancel) }
        }
    }

    if (pickingDate) {
        val pickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        viewModel.update { it.copy(date = LocalDate.fromEpochDays((millis / 86_400_000L).toInt())) }
                    }
                    pickingDate = false
                }) { Text(s.ok) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(s.cancel) } },
        ) { DatePicker(state = pickerState) }
    }
}
