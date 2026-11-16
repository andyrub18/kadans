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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import app.kadans.api.TodosApi
import app.kadans.billing.SubscriptionGate
import app.kadans.i18n.LocalStrings
import app.kadans.share.SharedText
import app.kadans.share.TodoDraft
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

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(s.newTodo, style = MaterialTheme.typography.headlineSmall)

            OutlinedTextField(
                value = state.title,
                onValueChange = { v -> viewModel.update { it.copy(title = SharedText.within(v, TodosApi.TITLE_LENGTH)) } },
                label = { Text(s.titleLabel) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.description,
                onValueChange = { v -> viewModel.update { it.copy(description = SharedText.within(v, TodosApi.DESCRIPTION_LENGTH)) } },
                label = { Text(s.descriptionOptional) },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.mode == TodoMode.OneTime,
                    onClick = { viewModel.update { it.copy(mode = TodoMode.OneTime) } },
                    label = { Text(s.oneTime) },
                )
                FilterChip(
                    selected = state.mode == TodoMode.Recurring,
                    onClick = { viewModel.update { it.copy(mode = TodoMode.Recurring) } },
                    label = { Text(s.recurring) },
                )
            }

            WhenSection(state, viewModel::update)

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(s.remindMe)
                Switch(checked = state.notify, onCheckedChange = { v -> viewModel.update { it.copy(notify = v) } })
            }
            if (state.notify) {
                ReminderLeadChooser(selected = state.notifyBefore, onSelect = { v -> viewModel.update { it.copy(notifyBefore = v) } })
            }

            if (state.error != null || state.errorCode != null) {
                Text(s.errorFor(state.errorCode, state.error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            val ready = state.canSubmit && CreateTodoViewModel.ruleReady(state, TimeZone.currentSystemDefault(), Clock.System.now())
            Button(onClick = { permission.beforeSaving(state.notify, viewModel::submit) }, enabled = ready, modifier = Modifier.fillMaxWidth()) {
                if (state.isLoading) CircularProgressIndicator(modifier = Modifier.padding(2.dp)) else Text(s.create)
            }
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(s.cancel) }
        }
    }
    ReminderPermissionDialog(permission)
}
