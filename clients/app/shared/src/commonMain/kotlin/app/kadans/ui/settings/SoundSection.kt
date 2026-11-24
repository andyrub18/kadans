package app.kadans.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.kadans.api.model.DevicePlatform
import app.kadans.config.devicePlatform
import app.kadans.i18n.LocalStrings
import app.kadans.i18n.StringsCatalog
import app.kadans.sound.ReminderSoundState
import app.kadans.sound.ReminderStyle
import app.kadans.sound.RingSettings
import app.kadans.ui.SecondaryButton
import org.koin.compose.koinInject

/**
 * How reminders sound on this device (ARCHITECTURE → "Sounds"). On a phone: what a reminder will do right now, said
 * in a sentence (the phone's own settings decide, and nobody knows that until told), the switch for ringing like an
 * alarm, the one for ringing until answered, the way to the phone's own page for the sound, and "Test". On a
 * computer, where the app plays the sounds itself: whether it does, and the same "ring until answered" and "Test".
 * Read again when the app comes back from the system's settings. It brings its own divider.
 */
@Composable
fun SoundSection(ring: RingSettings = koinInject()) {
    val s = LocalStrings.current
    val style by ring.style.collectAsState()
    val keepRinging by ring.keepRinging.collectAsState()
    val sounds by ring.sounds.collectAsState()
    var state by remember { mutableStateOf(ring.state()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state = ring.state() }

    val now = state
    if (now != null) {
        Text(s.sound.section, style = MaterialTheme.typography.titleMedium)
        Text(
            soundSentence(now, s),
            style = MaterialTheme.typography.bodySmall,
            color = if (now.heard) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
        SwitchLine(s.sound.alarmSwitch, s.sound.alarmHint, checked = style == ReminderStyle.Alarm) { on ->
            ring.choose(if (on) ReminderStyle.Alarm else ReminderStyle.Notification)
            state = ring.state()
        }
        SwitchLine(s.sound.keepRinging, s.sound.keepRingingHint, checked = keepRinging, onChange = ring::setKeepRinging)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(onClick = ring::openSystemSettings, modifier = Modifier.weight(1f)) { Text(s.sound.chooseSound, maxLines = 1) }
            SecondaryButton(onClick = ring::test, modifier = Modifier.weight(1f)) { Text(s.sound.test, maxLines = 1) }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
    } else if (devicePlatform() in Computers) {
        Text(s.sound.computerSection, style = MaterialTheme.typography.titleMedium)
        SwitchLine(s.sound.computerSwitch, s.sound.computerHint, checked = sounds, onChange = ring::setSounds)
        if (sounds) {
            SwitchLine(s.sound.keepRinging, s.sound.keepRingingHint, checked = keepRinging, onChange = ring::setKeepRinging)
            SecondaryButton(onClick = ring::test, modifier = Modifier.fillMaxWidth()) { Text(s.sound.test) }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
    }
}

private val Computers = setOf(DevicePlatform.Linux, DevicePlatform.Windows, DevicePlatform.MacOs)

/** A reminder that rings as things stand will be heard; the other states are worth a warning's colour. */
private val ReminderSoundState.heard: Boolean
    get() = this == ReminderSoundState.Alarm || this == ReminderSoundState.Sound

internal fun soundSentence(state: ReminderSoundState, s: StringsCatalog): String = when (state) {
    ReminderSoundState.Alarm -> s.sound.stateAlarm
    ReminderSoundState.AlarmVolumeOff -> s.sound.stateAlarmVolumeOff
    ReminderSoundState.Sound -> s.sound.stateSound
    ReminderSoundState.PhoneSilent -> s.sound.statePhoneSilent
    ReminderSoundState.PhoneVibrates -> s.sound.statePhoneVibrates
    ReminderSoundState.Muted -> s.sound.stateMuted
    ReminderSoundState.NotificationsOff -> s.reminders.notificationsOff
}

/** A switch with its name and, smaller, what it means: the whole line turns it. */
@Composable
private fun SwitchLine(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 56.dp)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * "How should reminders reach you?", asked once on a phone (ARCHITECTURE → "Sounds"): like an alarm, heard even on a
 * silent phone, which is the answer offered first, or like a notification. Nobody's reminders ring like an alarm
 * before they have said so here. Closed without an answer ([onLater]), it is asked again another time.
 */
@Composable
fun ReminderStyleDialog(onChoose: (ReminderStyle) -> Unit, onLater: () -> Unit) {
    val s = LocalStrings.current.sound
    var picked by remember { mutableStateOf(ReminderStyle.Alarm) }
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(s.question) },
        text = {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StyleOption(s.likeAlarm, s.likeAlarmHint, picked == ReminderStyle.Alarm) { picked = ReminderStyle.Alarm }
                StyleOption(s.likeNotification, s.likeNotificationHint, picked == ReminderStyle.Notification) { picked = ReminderStyle.Notification }
                Text(s.questionNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onChoose(picked) }) { Text(LocalStrings.current.ok) } },
    )
}

@Composable
private fun StyleOption(title: String, hint: String, selected: Boolean, onSelect: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        // On the dialog's white: the page's colour, the chosen one in the lilac of what is selected.
        color = if (selected) scheme.primaryContainer else scheme.background,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    ) {
        Row(Modifier.padding(start = 6.dp, end = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(10.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}
