package app.kadans.ui.todos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import app.kadans.i18n.LocalStrings
import app.kadans.ui.ChoiceChip
import app.kadans.ui.Choices
import app.kadans.ui.FieldLabel

/** How long before a todo's start its reminder comes. */
object ReminderLeads {
    /** At the start, 5, 10, 15, 30 minutes, 1 hour, 1 day. The server accepts up to 30 days. */
    val presets: List<Int> = listOf(0, 5, 10, 15, 30, 60, 24 * 60)

    const val DEFAULT: Int = 15

    /** The presets, plus a todo's own lead when it is none of them (set before the presets existed). */
    fun options(current: Int?): List<Int> = (presets + listOfNotNull(current)).distinct().sorted()
}

/** "When": one pill per lead, the [selected] one marked. Shown under the "Remind me" switch; [onCard] on its white card. */
@Composable
fun ReminderLeadChooser(selected: Int, onSelect: (Int) -> Unit, enabled: Boolean = true, onCard: Boolean = false) {
    val f = LocalStrings.current.todoForm
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldLabel(f.reminderWhen)
        Choices {
            ReminderLeads.options(selected).forEach { minutes ->
                ChoiceChip(
                    selected = minutes == selected,
                    onClick = { onSelect(minutes) },
                    enabled = enabled,
                    onCard = onCard,
                    label = { Text(f.reminderLead(minutes)) },
                )
            }
        }
    }
}
