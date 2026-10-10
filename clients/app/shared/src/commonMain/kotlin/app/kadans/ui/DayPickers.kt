package app.kadans.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
 * too (seven filter chips do not fit across one: they wrap and leave Sunday alone).
 */
@Composable
internal fun WeekDayPicker(selected: Set<ApiDayOfWeek>, onToggle: (ApiDayOfWeek) -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        mondayFirstDays.forEachIndexed { index, day ->
            DayToggle(s.weekdayShort[index], day in selected, onClick = { onToggle(day) }, Modifier.weight(1f))
        }
    }
}

/** The month's days, seven to a row as on a calendar, then "Last day" (-1): several can be picked. */
@Composable
internal fun MonthDayPicker(selected: Set<Int>, onToggle: (Int) -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        (1..31).chunked(7).forEach { row ->
            // Each cell carries its share of the gap, so the last row's wide cell lines up with the columns above.
            Row(Modifier.fillMaxWidth()) {
                row.forEach { day -> DayToggle("$day", day in selected, onClick = { onToggle(day) }, Modifier.weight(1f).padding(horizontal = 2.dp)) }
                if (row.size < 7) {
                    val span = Modifier.weight((7 - row.size).toFloat()).padding(horizontal = 2.dp)
                    DayToggle(s.repeat.lastDay, -1 in selected, onClick = { onToggle(-1) }, span)
                }
            }
        }
    }
}

/** The twelve months, three to a row (1–12): several can be picked. */
@Composable
internal fun MonthPicker(selected: Set<Int>, onToggle: (Int) -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        (1..12).chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { month ->
                    DayToggle(s.monthNames[month - 1].capitalized(), month in selected, onClick = { onToggle(month) }, Modifier.weight(1f))
                }
            }
        }
    }
}

/** One cell of the pickers, styled as the filter chips. */
@Composable
private fun DayToggle(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        selected = selected,
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.height(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
        }
    }
}

/** "janvier" → "Janvier": names written lowercase inside sentences, at the start of a label. */
internal fun String.capitalized(): String = replaceFirstChar { it.uppercase() }
