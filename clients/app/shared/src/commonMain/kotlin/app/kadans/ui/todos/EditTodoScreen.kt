package app.kadans.ui.todos

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kadans.i18n.LocalStrings
import kotlin.time.Clock
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun EditTodoScreen(
    todoId: String,
    onSaved: () -> Unit,
    onBack: () -> Unit,
    viewModel: EditTodoViewModel = koinViewModel(key = "edit-$todoId") { parametersOf(todoId) },
) {
    val state by viewModel.state.collectAsState()
    val s = LocalStrings.current
    val permission = rememberReminderPermissionAsk()
    LaunchedEffect(viewModel) {
        viewModel.saved.collect {
            permission.saved()
            onSaved()
        }
    }

    if (state.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(s.editTodo, style = MaterialTheme.typography.headlineSmall)

            OutlinedTextField(
                value = state.title,
                onValueChange = { v -> viewModel.update { it.copy(title = v) } },
                label = { Text(s.titleLabel) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.description,
                onValueChange = { v -> viewModel.update { it.copy(description = v) } },
                label = { Text(s.descriptionOptional) },
                modifier = Modifier.fillMaxWidth(),
            )

            // A recurring todo's rule, as the create form shows it, from the next occurrence on. Left alone, it is not sent.
            state.rule?.let { rule ->
                if (!state.ruleWorded) {
                    Text(s.repeat.madeElsewhere, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                WhenSection(rule, viewModel::updateRule, zone = state.ruleZone)
                if (state.ruleChanged) {
                    Text(s.repeat.editKeepsTouched, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(s.remindMe)
                Switch(checked = state.notify, onCheckedChange = { v -> viewModel.update { it.copy(notify = v) } })
            }
            if (state.notify) {
                ReminderLeadChooser(
                    selected = state.notifyBefore ?: ReminderLeads.DEFAULT,
                    onSelect = { v -> viewModel.update { it.copy(notifyBefore = v) } },
                )
            }

            if (state.error != null || state.errorCode != null) {
                Text(s.errorFor(state.errorCode, state.error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            val ruleReady = !state.ruleChanged || state.rule?.let {
                CreateTodoViewModel.ruleReady(it, state.ruleZone, Clock.System.now())
            } == true
            Button(
                onClick = { permission.beforeSaving(state.notify, viewModel::save) },
                enabled = state.canSave && ruleReady,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isSaving) CircularProgressIndicator(modifier = Modifier.padding(2.dp)) else Text(s.saveChanges)
            }
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(s.cancel) }
        }
    }
    ReminderPermissionDialog(permission)
}
