package app.kadans.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kadans.i18n.LocalStrings
import app.kadans.profile.TimeZoneCatalog
import app.kadans.profile.TimeZoneEntry
import kotlin.time.Clock

/** Settings → time zone: the account's zone, kept in line with this device or picked from the list. */
@Composable
fun TimeZoneSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    val s = LocalStrings.current
    val z = s.timeZone
    val accountZone = state.user?.timeZone
    val shown = remember(accountZone) { accountZone?.let { runCatching { TimeZoneCatalog.entry(it, Clock.System.now()) }.getOrNull() } }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(z.section, style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(shown?.let { "${it.city} · ${it.offset}" } ?: accountZone.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                if (accountZone != null) {
                    Text(accountZone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!state.followingDevice) {
                TextButton(onClick = viewModel::openTimeZonePicker, enabled = !state.isBusy) { Text(s.change) }
            }
        }
        if (state.deviceZone != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(z.followDevice, style = MaterialTheme.typography.bodyMedium)
                Switch(checked = state.followDevice, onCheckedChange = viewModel::setFollowDevice, enabled = !state.isBusy)
            }
        }
        Text(
            when {
                state.deviceZone == null -> z.deviceZoneUnusable
                state.followDevice -> z.followDeviceHint
                else -> z.manualHint
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.timeZoneSaved) {
            Text(z.saved, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
    }

    if (state.zonePickerOpen) {
        TimeZonePickerDialog(
            choices = viewModel.timeZoneChoices,
            selected = accountZone,
            onPick = viewModel::chooseTimeZone,
            onDismiss = viewModel::closeTimeZonePicker,
        )
    }
}

/** A searchable list of every zone: city, region and current offset ("Port-au-Prince", "America · UTC−4"). */
@Composable
fun TimeZonePickerDialog(choices: List<TimeZoneEntry>, selected: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val z = s.timeZone
    var query by remember { mutableStateOf("") }
    val matching = remember(query, choices) { choices.filter { TimeZoneCatalog.matches(it, query) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(z.choose) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(z.searchHint) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (matching.isEmpty()) {
                    Text(z.noMatch, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(matching, key = { it.id }) { entry ->
                        val isSelected = entry.id == selected
                        Column(Modifier.fillMaxWidth().clickable { onPick(entry.id) }.padding(vertical = 10.dp)) {
                            Text(
                                entry.city,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                listOf(entry.region, entry.offset).filter { it.isNotEmpty() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}
