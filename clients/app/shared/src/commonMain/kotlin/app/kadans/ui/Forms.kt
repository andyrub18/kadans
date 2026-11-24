package app.kadans.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kadans.i18n.LocalStrings
import app.kadans.ui.shell.KadansIcons

/*
 * What the forms are made of (ARCHITECTURE → "The look"): a page with its title and a way out, one column on a phone
 * and two in a wide window, and the choices as pills. The field itself is `KadansField`.
 */

/** Space between a form's parts. */
internal val FormGap = Arrangement.spacedBy(14.dp)

/**
 * A form on a page of its own: the round button that leaves it, its [title], then [content], which is told whether the
 * window is wide enough for two columns ([FormColumns]). It scrolls as one, its buttons last ([FormActions]).
 */
@Composable
fun FormPage(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(wide: Boolean) -> Unit,
) {
    val s = LocalStrings.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= WideWindow
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (wide) 32.dp else 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = if (wide) 920.dp else 520.dp).fillMaxWidth(), verticalArrangement = FormGap) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircleButton(KadansIcons.Close, s.close, onClose)
                    Text(
                        title,
                        style = if (wide) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                content(wide)
            }
        }
    }
}

/** A wide window's two columns, side by side. */
@Composable
fun FormColumns(first: @Composable ColumnScope.() -> Unit, second: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = FormGap, content = first)
        Column(Modifier.weight(1f), verticalArrangement = FormGap, content = second)
    }
}

/** A form's end: "Cancel" and the button that saves, which fills a phone's width and stands on the right of a wide one. */
@Composable
fun FormActions(
    wide: Boolean,
    confirm: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    enabled: Boolean,
    busy: Boolean = false,
) {
    val s = LocalStrings.current
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancel) { Text(s.cancel) }
        BigButton(confirm, onConfirm, if (wide) Modifier.widthIn(min = 200.dp) else Modifier.weight(1f), enabled = enabled, busy = busy)
    }
}

/** The small word over a group of choices: "Repeat", "On days", "From". */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** A group of choices under its [label], which sits closer to them than the form's parts do to each other. */
@Composable
fun FieldGroup(label: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label != null) FieldLabel(label)
        content()
    }
}

/** Pills that wrap onto as many lines as they need. */
@Composable
fun Choices(modifier: Modifier = Modifier, content: @Composable FlowRowScope.() -> Unit) {
    // A pill is 44 dp in a 48 dp touch target: the lines end up as far apart as the pills are.
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

/**
 * One of a few choices, as a pill: white until chosen, then purple. [onCard] is for a pill on a white card, where it is
 * the page's colour instead.
 */
@Composable
fun ChoiceChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onCard: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.alpha(if (enabled) 1f else 0.5f).semantics { role = Role.RadioButton },
        shape = CircleShape,
        color = when {
            selected -> scheme.primary
            onCard -> scheme.background
            else -> scheme.surfaceContainerHighest
        },
        contentColor = if (selected) scheme.onPrimary else scheme.onSurface,
    ) {
        Box(Modifier.heightIn(min = 44.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Bold)) {
                label()
            }
        }
    }
}

/** Two or three choices that share one white pill, the chosen one purple: "One-time / Recurring". */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    name: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().background(scheme.surfaceContainerHighest, CircleShape).padding(4.dp).selectableGroup()) {
        options.forEach { option ->
            val chosen = option == selected
            Box(
                Modifier.weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(CircleShape)
                    .background(if (chosen) scheme.primary else Color.Transparent)
                    .selectable(selected = chosen, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(option) })
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Three words share a phone's width: "Transferencia" gets smaller before it is cut.
                Text(
                    name(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (chosen) scheme.onPrimary else scheme.onSurfaceVariant,
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 15.sp, stepSize = 1.sp),
                )
            }
        }
    }
}

/** A round "−" or "+" beside a number that goes down or up. */
@Composable
fun StepButton(sign: String, onClick: () -> Unit, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
        color = if (enabled) scheme.surfaceContainerHighest else scheme.surfaceVariant,
        contentColor = if (enabled) scheme.onPrimaryContainer else scheme.outline,
    ) {
        Box(contentAlignment = Alignment.Center) { Text(sign, style = MaterialTheme.typography.titleLarge) }
    }
}

/**
 * A switch on a white card, the whole line pressed to turn it; [content] is what the switch brings with it when on
 * ("Remind me", then how long before).
 */
@Composable
fun SwitchCard(
    text: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(20.dp)
    Surface(modifier.fillMaxWidth(), shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Column {
            Row(
                Modifier.fillMaxWidth()
                    .clip(shape)
                    .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
                    .heightIn(min = 56.dp)
                    .padding(start = 16.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 12.dp))
                Switch(checked = checked, onCheckedChange = null)
            }
            if (checked && content != null) {
                Column(Modifier.padding(start = 16.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            }
        }
    }
}

/** A rule said in words, on a lilac pill with the sign for "it repeats": "Every week on Monday at 8:15 AM". */
@Composable
fun RuleWords(text: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.background(scheme.primaryContainer, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(KadansIcons.Repeat, contentDescription = null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = scheme.onPrimaryContainer)
    }
}
