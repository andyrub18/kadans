package app.kadans.ui.todos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import app.kadans.i18n.LocalStrings

/** How long before a todo's start its reminder comes. */
object ReminderLeads {
    /** At the start, 5, 10, 15, 30 minutes, 1 hour, 1 day. The server accepts up to 30 days. */
    val presets: List<Int> = listOf(0, 5, 10, 15, 30, 60, 24 * 60)

    const val DEFAULT: Int = 15

    /** The presets, plus a todo's own lead when it is none of them (set before the presets existed). */
    fun options(current: Int?): List<Int> = (presets + listOfNotNull(current)).distinct().sorted()
}

/** "When": one chip per lead, the [selected] one marked. Shown under the "Remind me" switch. */
@Composable
fun ReminderLeadChooser(selected: Int, onSelect: (Int) -> Unit, enabled: Boolean = true) {
    val f = LocalStrings.current.todoForm
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(f.reminderWhen, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReminderLeads.options(selected).forEach { minutes ->
                FilterChip(
                    selected = minutes == selected,
                    onClick = { onSelect(minutes) },
                    enabled = enabled,
                    label = { Text(f.reminderLead(minutes)) },
                )
            }
        }
    }
}
