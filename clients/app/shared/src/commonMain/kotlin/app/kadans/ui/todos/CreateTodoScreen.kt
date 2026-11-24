package app.kadans.ui.todos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import app.kadans.api.TodosApi
import app.kadans.billing.SubscriptionGate
import app.kadans.i18n.LocalStrings
import app.kadans.share.SharedText
import app.kadans.share.TodoDraft
import app.kadans.ui.FormActions
import app.kadans.ui.FormColumns
import app.kadans.ui.FormPage
import app.kadans.ui.KadansField
import app.kadans.ui.Segmented
import app.kadans.ui.SwitchCard
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The form for a new todo, filled in from a text another app shared (ARCHITECTURE → "Share to Kadans"): [draft]'s title
 * and description, the person's to change; nothing is saved before "Create". A share reaches the app past Home, which
 * is where a phone without a subscription meets its paywall: asked here first, and [onPaywall] leads to it.
 */
@Composable
fun SharedTodoScreen(
    draft: TodoDraft,
    onCreated: (String) -> Unit,
    onBack: () -> Unit,
    onPaywall: () -> Unit,
    gate: SubscriptionGate = koinInject(),
) {
    var open by remember { mutableStateOf(if (gate.appliesHere) null else true) }
    LaunchedEffect(Unit) { if (open == null) open = !gate.needsPaywall() }
    when (open) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        false -> LaunchedEffect(Unit) { onPaywall() }
        true -> CreateTodoScreen(onCreated = onCreated, onBack = onBack, viewModel = koinViewModel { parametersOf(draft) })
    }
}

@Composable
fun CreateTodoScreen(
    onCreated: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: CreateTodoViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val s = LocalStrings.current

    val permission = rememberReminderPermissionAsk()
    LaunchedEffect(viewModel) {
        viewModel.created.collect {
            permission.saved()
            onCreated(it)
        }
    }

    FormPage(s.newTodo, onBack) { wide ->
        // What it is and whether it reminds on one side, when it happens on the other; a phone reads them in order.
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
        val whenItIs: @Composable ColumnScope.() -> Unit = {
            Segmented(
                options = TodoMode.entries,
                selected = state.mode,
                name = { if (it == TodoMode.OneTime) s.oneTime else s.recurring },
                onSelect = { mode -> viewModel.update { it.copy(mode = mode) } },
            )
            WhenSection(state, viewModel::update)
        }
        val reminder: @Composable ColumnScope.() -> Unit = {
            SwitchCard(s.remindMe, state.notify, onChange = { v -> viewModel.update { it.copy(notify = v) } }) {
                ReminderLeadChooser(selected = state.notifyBefore, onSelect = { v -> viewModel.update { it.copy(notifyBefore = v) } }, onCard = true)
            }
        }
        if (wide) {
            FormColumns(first = { what(); reminder() }, second = whenItIs)
        } else {
            what()
            whenItIs()
            reminder()
        }

        if (state.error != null || state.errorCode != null) {
            Text(s.errorFor(state.errorCode, state.error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        val ready = state.canSubmit && CreateTodoViewModel.ruleReady(state, TimeZone.currentSystemDefault(), Clock.System.now())
        FormActions(
            wide = wide,
            confirm = s.create,
            onConfirm = { permission.beforeSaving(state.notify, viewModel::submit) },
            onCancel = onBack,
            enabled = ready,
            busy = state.isLoading,
        )
    }
    ReminderPermissionDialog(permission)
}
