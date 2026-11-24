package app.kadans.ui.settings

import app.kadans.ui.SecondaryButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.kadans.calendar.PhoneCalendar
import app.kadans.calendar.PhoneCalendarState
import app.kadans.i18n.LocalStrings
import org.koin.compose.koinInject

/**
 * The phone's calendar beside the todos (ARCHITECTURE → "The phone's calendar"), on a phone that has one Kadans can
 * read: its switch, what it does and that the events stay on the phone, and, with the switch on and no permission, the
 * button that asks for it (or opens the system's settings once Android no longer asks). Read again when the app comes
 * back from the system's question. It brings its own divider.
 */
@Composable
fun PhoneCalendarSection(calendar: PhoneCalendar = koinInject()) {
    val state by calendar.state.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { calendar.cameBack() }
    if (state == PhoneCalendarState.Unsupported) return
    val c = LocalStrings.current.phoneCalendar

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(c.section, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = state != PhoneCalendarState.Off,
            onCheckedChange = { wanted -> if (wanted) calendar.turnOn() else calendar.turnOff() },
        )
    }
    Text(c.sectionHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state == PhoneCalendarState.NeedsPermission) {
        Text(
            c.needsPermission,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
        SecondaryButton(onClick = calendar::allow, modifier = Modifier.fillMaxWidth()) { Text(c.allow) }
    }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
}

/**
 * The Calendar tab's offer, made once on a phone that has a calendar and the switch off: that is where someone looks
 * for their meetings. "Turn on" is the switch (Android's own question follows); either answer ends the offer for good.
 */
@Composable
fun PhoneCalendarOffer(modifier: Modifier = Modifier, calendar: PhoneCalendar = koinInject()) {
    val offered by calendar.offer.collectAsState()
    // Back from Android's question: the answer is read, and the month shows the events at once.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { calendar.cameBack() }
    if (!offered) return
    val c = LocalStrings.current.phoneCalendar

    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Text(c.offerTitle, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp))
        Text(
            c.offerText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = calendar::offerDeclined) { Text(c.notNow) }
            TextButton(onClick = calendar::turnOn) { Text(c.turnOn) }
        }
    }
}
