package app.kadans.ui.todos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kadans.api.TodosApi
import app.kadans.share.SharedText
import app.kadans.i18n.LocalStrings
import app.kadans.ui.FormActions
import app.kadans.ui.FormColumns
import app.kadans.ui.FormGap
import app.kadans.ui.FormPage
import app.kadans.ui.KadansField
import app.kadans.ui.SwitchCard
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

    FormPage(s.editTodo, onBack) { wide ->
        val what: @Composable ColumnScope.() -> Unit = {
            KadansField(
                value = state.title,
                onValueChange = { v -> viewModel.update { it.copy(title = SharedText.within(v, TodosApi.TITLE_LENGTH)) } },
                label = { Text(s.titleLabel) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            KadansField(
                value = state.description,
                onValueChange = { v -> viewModel.update { it.copy(description = SharedText.within(v, TodosApi.DESCRIPTION_LENGTH)) } },
                label = { Text(s.descriptionOptional) },
                minLines = if (wide) 3 else 1,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val reminder: @Composable ColumnScope.() -> Unit = {
            SwitchCard(s.remindMe, state.notify, onChange = { v -> viewModel.update { it.copy(notify = v) } }) {
                ReminderLeadChooser(
                    selected = state.notifyBefore ?: ReminderLeads.DEFAULT,
                    onSelect = { v -> viewModel.update { it.copy(notifyBefore = v) } },
                    onCard = true,
                )
            }
        }
        // A recurring todo's rule, as the create form shows it, from the next occurrence on. Left alone, it is not sent.
        val rule = state.rule
        val itsRule: @Composable ColumnScope.() -> Unit = {
            if (rule != null) {
                if (!state.ruleWorded) {
                    Text(s.repeat.madeElsewhere, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                WhenSection(rule, viewModel::updateRule, zone = state.ruleZone)
                if (state.ruleChanged) {
                    Text(s.repeat.editKeepsTouched, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        when {
            wide && rule != null -> FormColumns(first = { what(); reminder() }, second = itsRule)
            // A one-time todo has one column's worth: it keeps a column's width in a wide window.
            wide -> Column(Modifier.widthIn(max = 480.dp), verticalArrangement = FormGap) { what(); reminder() }
            else -> {
                what()
                itsRule()
                reminder()
            }
        }

        if (state.error != null || state.errorCode != null) {
            Text(s.errorFor(state.errorCode, state.error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        val ruleReady = !state.ruleChanged || state.rule?.let {
            CreateTodoViewModel.ruleReady(it, state.ruleZone, Clock.System.now())
        } == true
        FormActions(
            wide = wide,
            confirm = s.saveChanges,
            onConfirm = { permission.beforeSaving(state.notify, viewModel::save) },
            onCancel = onBack,
            enabled = state.canSave && ruleReady,
            busy = state.isSaving,
        )
    }
    ReminderPermissionDialog(permission)
}
