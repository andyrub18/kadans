package app.kadans.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp

/** What a picker field opens, which its icon shows. */
enum class PickerKind { Date, Time }

/**
 * A field whose value comes from a picker, opened by tapping it anywhere, or with Enter or Space from the keyboard. It
 * replaced a "Pick" button beside the value, which on a 360 dp phone in French or Kreyòl left the value too little
 * room ("2026-10-1" over "0"). A calendar or a clock says what it opens; [extra] is a button before it ("End of the
 * day"), which keeps its own taps.
 */
@Composable
internal fun PickerField(
    value: String,
    label: String,
    kind: PickerKind,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    isError: Boolean = false,
    supportingText: String? = null,
    extra: (@Composable () -> Unit)? = null,
) {
    val pick by rememberUpdatedState(onPick)
    val focus = LocalFocusManager.current
    val interactions = remember { MutableInteractionSource() }
    LaunchedEffect(interactions) {
        interactions.interactions.collect {
            if (it is PressInteraction.Release) {
                // A tap leaves no highlighted field behind the picker; a keyboard keeps its place (below).
                focus.clearFocus()
                pick()
            }
        }
    }
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        singleLine = true,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                extra?.invoke()
                Icon(if (kind == PickerKind.Date) CalendarIcon else ClockIcon, contentDescription = null, Modifier.padding(end = 12.dp))
            }
        },
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        interactionSource = interactions,
        modifier = modifier.onPreviewKeyEvent { event ->
            val opens = event.type == KeyEventType.KeyUp && event.key in setOf(Key.Enter, Key.NumPadEnter, Key.Spacebar)
            if (opens) pick()
            opens
        },
    )
}

/** A calendar page: drawn here, as the app carries no icon set. */
private val CalendarIcon: ImageVector = icon("calendar") {
    moveTo(5f, 5f)
    lineTo(19f, 5f)
    arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 21f, y1 = 7f)
    lineTo(21f, 19f)
    arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 19f, y1 = 21f)
    lineTo(5f, 21f)
    arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 3f, y1 = 19f)
    lineTo(3f, 7f)
    arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 5f, y1 = 5f)
    close()
    moveTo(3f, 10f)
    lineTo(21f, 10f)
    moveTo(8f, 3f)
    lineTo(8f, 7f)
    moveTo(16f, 3f)
    lineTo(16f, 7f)
}

/** A clock face at a quarter past. */
private val ClockIcon: ImageVector = icon("clock") {
    moveTo(21f, 12f)
    arcTo(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 3f, y1 = 12f)
    arcTo(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 21f, y1 = 12f)
    close()
    moveTo(12f, 7f)
    lineTo(12f, 12f)
    lineTo(15.5f, 14f)
}

private fun icon(name: String, outline: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .path(
            stroke = SolidColor(Color.Black), // Icon tints it with the field's colour
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = outline,
        )
        .build()
