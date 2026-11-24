package app.kadans.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** A field's corners, and those of whatever stands in a form beside one. */
val FieldShape: Shape = RoundedCornerShape(16.dp)

/**
 * The app's text field (ARCHITECTURE → "The look"): white and round on the cream page, its label inside it, a faint
 * line around it so that it also shows on a white card or in a dialog, and the purple line while it is written in.
 * Every field is this one: Material's own are see-through or underlined, and the line here is drawn around the field
 * alone, not around the sentence under it.
 */
@Composable
fun KadansField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scheme = MaterialTheme.colorScheme
    val fill = scheme.surfaceContainerHighest
    val line = when {
        isError -> BorderStroke(2.dp, scheme.error)
        focused && !readOnly -> BorderStroke(2.dp, scheme.primary)
        else -> BorderStroke(1.dp, scheme.outlineVariant)
    }
    // Material's filled field, less the line under it: the label and the helper sentence keep its colours.
    val colors = TextFieldDefaults.colors(
        focusedContainerColor = fill,
        unfocusedContainerColor = fill,
        disabledContainerColor = fill,
        errorContainerColor = fill,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
        errorIndicatorColor = Color.Transparent,
    )
    val text = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.38f)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.defaultMinSize(minWidth = TextFieldDefaults.MinWidth, minHeight = TextFieldDefaults.MinHeight),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = LocalTextStyle.current.merge(TextStyle(color = text)),
        cursorBrush = SolidColor(if (isError) scheme.error else scheme.primary),
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        interactionSource = source,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        decorationBox = { inner ->
            TextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = inner,
                enabled = enabled,
                singleLine = singleLine,
                visualTransformation = visualTransformation,
                interactionSource = source,
                isError = isError,
                label = label,
                placeholder = placeholder,
                trailingIcon = trailingIcon,
                supportingText = supportingText,
                shape = FieldShape,
                colors = colors,
                container = { Box(Modifier.background(fill, FieldShape).border(line, FieldShape)) },
            )
        },
    )
}
