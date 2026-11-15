package app.kadans.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kadans.i18n.LocalStrings
import app.kadans.widget.TodayWidget
import org.koin.compose.koinInject

/**
 * The home-screen widget (ARCHITECTURE → "The home-screen widget"), where the home screen takes one when an app asks
 * (Android 8+, most launchers): what it is, and a button that has the home screen offer to add it. Few people look for
 * widgets in the launcher's own list. Nothing elsewhere: no widgets on a computer, and a launcher that does not take the
 * request still lists the widget itself. It brings its own divider.
 */
@Composable
fun WidgetSection(widget: TodayWidget = koinInject()) {
    val offered = remember { widget.canBeAdded }
    if (!offered) return
    val w = LocalStrings.current.widget

    Text(w.section, style = MaterialTheme.typography.titleMedium)
    Text(w.sectionHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(onClick = widget::addToHomeScreen, modifier = Modifier.fillMaxWidth()) { Text(w.addToHome) }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
}
