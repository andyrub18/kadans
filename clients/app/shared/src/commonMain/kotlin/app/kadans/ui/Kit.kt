package app.kadans.ui

import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout
import app.kadans.i18n.LocalStrings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kadans.ui.shell.KadansIcons

/*
 * The look's few building blocks (ARCHITECTURE → "The look"), so that a card, a ring or a large button is drawn once
 * and every screen has the same one.
 */

/** The soft, purple-tinted shadow that lifts a card or a large button off the page. */
@Composable
fun Modifier.softShadow(shape: Shape, elevation: Dp = 10.dp): Modifier {
    val shadow = LocalKadansColors.current.shadow
    return shadow(elevation, shape, clip = false, ambientColor = shadow, spotColor = shadow)
}

/** A card that stands out: the day's progress, the balance, a form's group. A list's own lines are plain `Card`s. */
@Composable
fun KadansCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    padding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = modifier.softShadow(shape), shape = shape, color = color, contentColor = contentColor) {
        Column(Modifier.padding(padding), content = content)
    }
}

/**
 * A ring that fills clockwise from the top: [progress] from 0 to 1, with whatever it is about in its middle. It says
 * nothing to a screen reader by itself: give [modifier] the sentence.
 */
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    stroke: Dp = 11.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    track: Color = LocalKadansColors.current.ringTrack,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val width = stroke.toPx()
            val inset = width / 2
            val arc = Size(size.width - width, size.height - width)
            drawArc(track, 0f, 360f, useCenter = false, topLeft = Offset(inset, inset), size = arc, style = Stroke(width))
            val sweep = 360f * progress.coerceIn(0f, 1f)
            if (sweep > 0f) {
                drawArc(color, -90f, sweep, useCenter = false, topLeft = Offset(inset, inset), size = arc, style = Stroke(width, cap = StrokeCap.Round))
            }
        }
        content()
    }
}

/**
 * A todo's circle: filled and ticked when [done], else empty and, with [onClick], pressed to mark it done.
 * [description] is what a screen reader says for it ("Mark “Water the plants” as done").
 */
@Composable
fun TickCircle(
    done: Boolean,
    description: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val k = LocalKadansColors.current
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .then(if (onClick != null && !done) Modifier.clickable(role = Role.Checkbox, onClick = onClick) else Modifier)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (done) {
            Box(Modifier.size(28.dp).background(k.done, CircleShape), contentAlignment = Alignment.Center) {
                Icon(KadansIcons.Check, contentDescription = null, tint = k.onDone, modifier = Modifier.size(18.dp))
            }
        } else {
            Box(
                Modifier.size(28.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                    .border(2.5.dp, if (emphasized) MaterialTheme.colorScheme.primary else k.circle, CircleShape),
            )
        }
    }
}

/** A small word on a tinted pill: "Focus", "Pending", a time. Not pressed. */
@Composable
fun Tag(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = content,
        maxLines = 1,
        modifier = modifier.background(container, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** A section's title with, on its right, the one thing to do about the section ("New account", "Show history"). */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

/** A round white button holding one icon: back, the bell, Settings. */
@Composable
fun CircleButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(46.dp).softShadow(CircleShape, 8.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = description, modifier = Modifier.size(22.dp)) }
    }
}

/**
 * The way back from a page: the arrow and the word in one white pill, pressed anywhere on it. An arrow alone beside
 * a title read as one thing ("← Settings"), and the title did nothing when pressed.
 */
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    Surface(
        onClick = onClick,
        modifier = modifier.softShadow(CircleShape, 8.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier.heightIn(min = 46.dp).padding(start = 12.dp, end = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(KadansIcons.Back, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(s.back, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/**
 * The top of a page opened over the app's frame (Settings, the notifications): [BackButton] on the left, the page's
 * [title] in the middle of the page, and [action] on the right when the page has one. A title too long to stand in the
 * middle takes the room between the two instead, so that it is cut as late as it can be.
 */
@Composable
fun PageHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Layout(
        content = {
            BackButton(onBack)
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Box { action?.invoke() }
        },
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val back = measurables[0].measure(loose)
        val end = measurables[2].measure(loose)
        val gap = 12.dp.roundToPx()
        val side = maxOf(back.width, end.width)
        val middle = constraints.maxWidth - 2 * (side + gap)
        val inMiddle = middle >= measurables[1].maxIntrinsicWidth(constraints.maxHeight)
        val width = (if (inMiddle) middle else constraints.maxWidth - back.width - end.width - 2 * gap).coerceAtLeast(0)
        val name = measurables[1].measure(Constraints.fixedWidth(width))
        val height = maxOf(back.height, end.height, name.height)
        layout(constraints.maxWidth, height) {
            back.placeRelative(0, (height - back.height) / 2)
            end.placeRelative(constraints.maxWidth - end.width, (height - end.height) / 2)
            name.placeRelative(if (inMiddle) side + gap else back.width + gap, (height - name.height) / 2)
        }
    }
}

/**
 * A thin line that fills with [progress], or runs while something loads when there is none. Material's own, given a
 * track that shows: its default one is `secondaryContainer`, which the theme makes the purple of the line itself.
 */
@Composable
fun ProgressLine(modifier: Modifier = Modifier, progress: (() -> Float)? = null) {
    val track = LocalKadansColors.current.ringTrack
    if (progress == null) {
        LinearProgressIndicator(modifier, trackColor = track)
    } else {
        LinearProgressIndicator(progress = progress, modifier = modifier, trackColor = track)
    }
}

/** A screen's main action: a tall purple pill with a shadow ("Start focus session", "Create"); [busy] while it is being done. */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    Button(
        onClick = { if (!busy) onClick() },
        enabled = enabled,
        modifier = (if (enabled) modifier.softShadow(CircleShape) else modifier).heightIn(min = 56.dp),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
    ) {
        if (busy) {
            // What it started is on its way: the wheel takes the icon's place, the word stays.
            CircularProgressIndicator(Modifier.size(20.dp), color = LocalContentColor.current, strokeWidth = 2.5.dp)
            Spacer(Modifier.width(8.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The second action beside a [BigButton]: as tall, outlined ("Next phase"). */
@Composable
fun BigOutlinedButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 56.dp),
        border = androidx.compose.foundation.BorderStroke(2.dp, if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * An action that is not the screen's main one: outlined, white inside, its words in the app's purple (Material writes
 * them in grey, which reads as switched off).
 */
@Composable
fun SecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        content = content,
    )
}

/** A window this wide lays a screen out in two columns, and the destinations in a column on its left. */
val WideWindow: Dp = 840.dp

/** Space between a screen's blocks. */
internal val Block = Arrangement.spacedBy(12.dp)
