package app.kadans.ui.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kadans.api.model.NotificationResponse
import app.kadans.i18n.LocalStrings
import kotlinx.datetime.TimeZone
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun NotificationsScreen(
    onOpenTodo: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: NotificationsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val s = LocalStrings.current
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("← " + s.back) }
                Text(s.notificationsTitle, style = MaterialTheme.typography.titleLarge)
                TextButton(
                    onClick = viewModel::markAllRead,
                    enabled = (state as? NotificationsUiState.Content)?.unread?.let { it > 0 } == true,
                ) { Text(s.markAllRead) }
            }
        },
    ) { padding ->
        when (val current = state) {
            is NotificationsUiState.Loading ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is NotificationsUiState.Error ->
                Column(
                    Modifier.fillMaxSize().padding(padding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(s.errorFor(current.code, current.message), color = MaterialTheme.colorScheme.error)
                    Button(onClick = viewModel::refresh, modifier = Modifier.padding(top = 12.dp)) { Text(s.retry) }
                }
            is NotificationsUiState.Content ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                    LazyColumn(
                        modifier = Modifier.widthIn(max = 560.dp).fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (current.items.isEmpty()) {
                            item { Text(s.noNotificationsYet, style = MaterialTheme.typography.bodyMedium) }
                        }
                        items(current.items, key = { it.id }) { notification ->
                            NotificationCard(
                                notification = notification,
                                onClick = {
                                    viewModel.markRead(notification.id)
                                    notification.data?.get("todoId")?.takeIf { it.isNotBlank() }?.let(onOpenTodo)
                                },
                            )
                        }
                        if (current.hasMore) {
                            item {
                                TextButton(
                                    onClick = viewModel::loadMore,
                                    enabled = !current.isLoadingMore,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(s.loadMore) }
                            }
                        }
                    }
                }
        }
    }
}

@Composable
private fun NotificationCard(notification: NotificationResponse, onClick: () -> Unit) {
    val unread = notification.readAt == null
    val timeZone = remember { TimeZone.currentSystemDefault() }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = if (unread) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                (if (unread) "● " else "") + notification.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
            )
            Text(notification.body, style = MaterialTheme.typography.bodyMedium)
            Text(
                NotificationsViewModel.formatTimestamp(notification.createdAt, timeZone),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
