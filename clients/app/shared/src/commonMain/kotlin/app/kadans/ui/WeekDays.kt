package app.kadans.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.kadans.api.model.ApiDayOfWeek
import app.kadans.i18n.LocalStrings
import kotlinx.datetime.DayOfWeek

/** The week as the forms and the calendar show it: Monday first, in the order of `StringsCatalog.weekdayShort`. */
internal val mondayFirstDays: List<ApiDayOfWeek> = listOf(
    ApiDayOfWeek.Monday, ApiDayOfWeek.Tuesday, ApiDayOfWeek.Wednesday, ApiDayOfWeek.Thursday,
    ApiDayOfWeek.Friday, ApiDayOfWeek.Saturday, ApiDayOfWeek.Sunday,
)

internal fun DayOfWeek.toApi(): ApiDayOfWeek = when (this) {
    DayOfWeek.MONDAY -> ApiDayOfWeek.Monday
    DayOfWeek.TUESDAY -> ApiDayOfWeek.Tuesday
    DayOfWeek.WEDNESDAY -> ApiDayOfWeek.Wednesday
    DayOfWeek.THURSDAY -> ApiDayOfWeek.Thursday
    DayOfWeek.FRIDAY -> ApiDayOfWeek.Friday
    DayOfWeek.SATURDAY -> ApiDayOfWeek.Saturday
    DayOfWeek.SUNDAY -> ApiDayOfWeek.Sunday
}

/**
 * The seven days as one row of toggles sharing the form's width, so "Monday to Friday" reads at a glance on a phone
 * too (seven filter chips do not fit across one: they wrap and leave Sunday alone). Styled as the filter chips.
 */
@Composable
internal fun WeekDayPicker(selected: Set<ApiDayOfWeek>, onToggle: (ApiDayOfWeek) -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        mondayFirstDays.forEachIndexed { index, day ->
            val on = day in selected
            Surface(
                selected = on,
                onClick = { onToggle(day) },
                shape = MaterialTheme.shapes.small,
                color = if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                contentColor = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                border = if (on) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.weight(1f).height(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(s.weekdayShort[index], style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
