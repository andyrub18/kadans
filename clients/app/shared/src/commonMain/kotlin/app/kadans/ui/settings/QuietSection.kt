package app.kadans.ui.settings

import app.kadans.ui.SecondaryButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Card
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.kadans.i18n.LocalStrings
import app.kadans.quiet.FocusQuiet
import app.kadans.quiet.QuietAccess
import app.kadans.quiet.QuietState
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Do Not Disturb during focus (ARCHITECTURE → "Do Not Disturb during focus"), on a phone that can (Android 10 and
 * later): its switch, what it does, and, with the switch on, what Android still has to allow, one thing at a time, each
 * with the button that opens the system's screen for it. Read again when the app comes back from there. It brings its
 * own divider.
 */
@Composable
fun QuietSection(quiet: FocusQuiet = koinInject()) {
    val state by quiet.state.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { quiet.cameBack() }
    if (state == QuietState.Unsupported) return
    val q = LocalStrings.current.quiet
    val scope = rememberCoroutineScope()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(q.section, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = state != QuietState.Off,
            onCheckedChange = { wanted -> scope.launch { if (wanted) quiet.turnOn() else quiet.turnOff() } },
        )
    }
    Text(q.sectionHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val missing = when (state) {
        QuietState.NeedsDoNotDisturbAccess -> q.needsDoNotDisturb
        QuietState.NeedsExactAlarms -> q.needsExactAlarms
        else -> null
    }
    if (missing != null) {
        Text(missing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
        SecondaryButton(onClick = quiet::openSettings, modifier = Modifier.fillMaxWidth()) {
            Text(LocalStrings.current.reminders.openSettings)
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
}

/**
 * The session screen's offer, made once on a phone that can go quiet and has the switch off: few people would look for
 * it in Settings. "Turn on" is the switch; either answer ends the offer for good. Android's screens follow for what it
 * has to allow, one after the other: back from the first, the second opens by itself, each once at most (someone who
 * comes back without allowing is not sent there again; Settings says what is missing).
 */
@Composable
fun QuietOffer(modifier: Modifier = Modifier, quiet: FocusQuiet = koinInject()) {
    val offered by quiet.offer.collectAsState()
    val scope = rememberCoroutineScope()
    // What Android's screen was last opened for, after "Turn on" here; null: nothing is being asked.
    var asked by rememberSaveable { mutableStateOf<QuietAccess?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val last = asked ?: return@LifecycleEventEffect
        quiet.cameBack()
        val missing = when (quiet.state.value) {
            QuietState.NeedsDoNotDisturbAccess -> QuietAccess.DoNotDisturbOff
            QuietState.NeedsExactAlarms -> QuietAccess.ExactAlarmsOff
            else -> null
        }
        asked = missing?.takeIf { it != last }
        if (asked != null) quiet.openSettings()
    }
    if (!offered) return
    val q = LocalStrings.current.quiet

    Card(modifier = modifier.fillMaxWidth()) {
        Text(q.offerTitle, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp))
        Text(
            q.offerText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = quiet::offerDeclined) { Text(q.notNow) }
            TextButton(onClick = { scope.launch { asked = quiet.turnOn().takeIf { it != QuietAccess.Ready } } }) { Text(q.turnOn) }
        }
    }
}
