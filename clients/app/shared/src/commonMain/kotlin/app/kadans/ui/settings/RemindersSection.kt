package app.kadans.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.kadans.api.model.DevicePlatform
import app.kadans.config.devicePlatform
import app.kadans.i18n.LocalStrings
import app.kadans.reminders.LocalReminders
import app.kadans.reminders.ReminderAccess
import org.koin.compose.koinInject

/**
 * Whether this phone rings reminders itself, and the way to turn it on (ARCHITECTURE → "Reminders ring on the phone").
 * Only on a phone that can (Android for now): the desktop's timer is always on, with nothing to turn on. Read again
 * when the app comes back from the system's settings. It brings its own divider.
 */
@Composable
fun RemindersSection(reminders: LocalReminders = koinInject()) {
    val phone = remember { devicePlatform() in setOf(DevicePlatform.Android, DevicePlatform.Ios) }
    var access by remember { mutableStateOf(reminders.access()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access = reminders.access() }
    if (!phone || access == ReminderAccess.Unsupported) return
    val r = LocalStrings.current.reminders

    Text(r.section, style = MaterialTheme.typography.titleMedium)
    Text(
        when (access) {
            ReminderAccess.Ready -> r.ringsHere
            ReminderAccess.ExactAlarmsOff -> r.exactAlarmsOff
            else -> r.notificationsOff
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (access != ReminderAccess.Ready) {
        OutlinedButton(onClick = reminders::openSettings, modifier = Modifier.fillMaxWidth()) { Text(r.openSettings) }
    }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
}
