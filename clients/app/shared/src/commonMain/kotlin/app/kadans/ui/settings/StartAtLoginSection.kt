package app.kadans.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.kadans.i18n.LocalStrings
import app.kadans.startup.StartAtLogin
import app.kadans.updates.AppUpdates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * This computer (installed desktop app only): opening Kadans with the session, read again when the window comes back
 * since the system's own settings can change it (files and the registry are touched off the window's thread); then the
 * app's version, and a newer one when the update check found it. It brings its own divider.
 */
@Composable
fun StartAtLoginSection(startAtLogin: StartAtLogin = koinInject(), updates: AppUpdates = koinInject()) {
    if (!startAtLogin.available) return
    val d = LocalStrings.current.desktop
    val u = LocalStrings.current.updates
    val newer by updates.available.collectAsState()
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(startAtLogin.isEnabled()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { enabled = startAtLogin.isEnabled() }

    Text(d.section, style = MaterialTheme.typography.titleMedium)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(d.startAtLogin, modifier = Modifier.weight(1f))
        Switch(
            checked = enabled,
            onCheckedChange = { wanted ->
                enabled = wanted
                scope.launch {
                    enabled = withContext(Dispatchers.Default) {
                        startAtLogin.setEnabled(wanted)
                        startAtLogin.isEnabled()
                    }
                }
            },
        )
    }
    Text(d.startAtLoginHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(u.version(updates.currentVersion), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
    newer?.let { notice ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(u.available(notice.version), color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { runCatching { uriHandler.openUri(AppUpdates.DOWNLOAD_PAGE) } }) { Text(u.download) }
        }
        notice.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
}
