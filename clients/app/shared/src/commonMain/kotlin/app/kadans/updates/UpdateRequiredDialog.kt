package app.kadans.updates

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import app.kadans.i18n.LocalStrings
import org.koin.compose.koinInject

/**
 * This version is older than the oldest that still works with the server (latest.json's minimumVersion, set by a
 * release that changed the API): asked once per run, with the download page a click away. Only the desktop's update
 * check ever sets it.
 */
@Composable
fun UpdateRequiredDialog(updates: AppUpdates = koinInject()) {
    val notice by updates.available.collectAsState()
    var dismissed by remember { mutableStateOf<String?>(null) }
    val required = notice?.takeIf { it.required && it.version != dismissed } ?: return
    val u = LocalStrings.current.updates
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = { dismissed = required.version },
        title = { Text(u.requiredTitle) },
        text = { Text(required.notes ?: u.requiredText) },
        confirmButton = {
            TextButton(onClick = {
                runCatching { uriHandler.openUri(AppUpdates.DOWNLOAD_PAGE) }
                dismissed = required.version
            }) { Text(u.download) }
        },
        dismissButton = { TextButton(onClick = { dismissed = required.version }) { Text(u.later) } },
    )
}
