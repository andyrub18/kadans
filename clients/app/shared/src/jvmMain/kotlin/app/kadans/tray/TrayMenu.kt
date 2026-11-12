package app.kadans.tray

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kadans.ui.LocalDarkTheme
import app.kadans.ui.brand.drawKadansMark
import java.awt.Point
import java.awt.Rectangle

/**
 * The tray icon's menu on Windows, drawn by Kadans (the design approved on 2026-11-12). Java's own tray menu is Windows'
 * old classic one: small type, thin lines, never dark, and it cannot be styled. This one has rounded corners, a soft
 * shadow and 40 dp rows with an icon each: the Kadans clock beside "Open Kadans", a separator, a power icon beside
 * "Quit". It is light or dark as the app is (Settings → Appearance) and in its language. The pointer or the arrow keys
 * pick a row, a click or Enter takes it, and Esc closes the menu.
 */
@Composable
internal fun TrayMenu(
    openLabel: String,
    quitLabel: String,
    onOpen: () -> Unit,
    onQuit: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = LocalDarkTheme.current
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    val highlight = colors.primary.copy(alpha = if (dark) 0.14f else 0.10f)
    val rows = listOf(
        MenuRow(openLabel, onOpen) { color -> drawKadansMark(color, small = true) },
        MenuRow(quitLabel, onQuit) { color -> drawPower(color) },
    )
    // -1: no row picked yet, as Windows' own menus open.
    var picked by remember { mutableIntStateOf(-1) }
    val focus = remember { FocusRequester() }

    Column(
        modifier
            .width(TrayMenuLayout.WIDTH.dp)
            .shadow(if (dark) 18.dp else 14.dp, shape)
            .background(if (dark) colors.surfaceContainerHigh else colors.surfaceContainerLowest)
            .border(1.dp, if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.08f), shape)
            .padding(TrayMenuLayout.PADDING.dp)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val outcome = menuKey(event.key, picked, rows.size) ?: return@onPreviewKeyEvent false
                picked = outcome.picked
                outcome.take?.let { rows[it].action() }
                if (outcome.close) onDismiss()
                true
            },
        verticalArrangement = Arrangement.spacedBy(TrayMenuLayout.GAP.dp),
    ) {
        rows.forEachIndexed { index, row ->
            if (index > 0) {
                Box(
                    Modifier
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.08f)),
                )
            }
            MenuItem(
                row = row,
                picked = picked == index,
                highlight = highlight,
                iconColor = if (index == 0) colors.primary else colors.onSurfaceVariant,
                textColor = colors.onSurface,
                onHover = { hovered -> if (hovered) picked = index else if (picked == index) picked = -1 },
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

private class MenuRow(val label: String, val action: () -> Unit, val icon: DrawScope.(Color) -> Unit)

/** What a key did in the menu: the row now [picked] (-1: none), the row to [take], or [close] it. */
internal data class MenuKey(val picked: Int, val take: Int? = null, val close: Boolean = false)

/**
 * The menu's keys, with [picked] picked (-1: none yet) among [rows]: the arrows move round the rows (up from none: the
 * last), Enter or Space takes the picked one (none: nothing), Esc closes. Null: not a key of the menu's.
 */
internal fun menuKey(key: Key, picked: Int, rows: Int): MenuKey? = when (key) {
    Key.DirectionDown -> MenuKey((picked + 1).mod(rows))
    Key.DirectionUp -> MenuKey((if (picked < 0) rows - 1 else picked - 1).mod(rows))
    Key.Enter, Key.NumPadEnter, Key.Spacebar -> MenuKey(picked, take = picked.takeIf { it in 0 until rows })
    Key.Escape -> MenuKey(picked, close = true)
    else -> null
}

@Composable
private fun MenuItem(row: MenuRow, picked: Boolean, highlight: Color, iconColor: Color, textColor: Color, onHover: (Boolean) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    LaunchedEffect(hovered) { onHover(hovered) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(TrayMenuLayout.ROW.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (picked) highlight else Color.Transparent)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = row.action)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Canvas(Modifier.size(18.dp)) { row.icon(this, iconColor) }
        Text(row.label, color = textColor, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A power symbol: a stroke down from the top, in a ring open at the top. */
private fun DrawScope.drawPower(color: Color) {
    val unit = size.minDimension / 24f
    val stroke = 2f * unit
    drawLine(color, Offset(12f * unit, 3f * unit), Offset(12f * unit, 11f * unit), strokeWidth = stroke, cap = StrokeCap.Round)
    drawArc(
        color,
        startAngle = -50f,
        sweepAngle = 280f,
        useCenter = false,
        topLeft = Offset(4f * unit, 5f * unit),
        size = Size(16f * unit, 16f * unit),
        style = Stroke(width = stroke, cap = StrokeCap.Round),
    )
}

/** The menu's measures, and where it opens. */
internal object TrayMenuLayout {
    const val WIDTH = 236
    const val ROW = 40
    const val PADDING = 4
    const val GAP = 2

    /** Two rows, the separator (1 dp and 2 dp above and below it) and the gaps around it, inside the padding. */
    const val HEIGHT = PADDING * 2 + ROW * 2 + (1 + 2 * 2) + GAP * 2

    /** Room around the menu in its window for the shadow to fall. */
    const val SHADOW_ROOM = 16

    /**
     * Where the menu's top-left corner goes for a click at [click] on a screen whose usable area (the taskbar left out) is
     * [screen]: above the click and to its left, as Windows opens a tray icon's menu, below or to the right where the
     * screen ends first (a taskbar at the top or the left), and always whole on the screen.
     */
    fun place(click: Point, screen: Rectangle, width: Int = WIDTH, height: Int = HEIGHT): Point {
        var x = click.x - width
        var y = click.y - height
        if (x < screen.x) x = click.x
        if (y < screen.y) y = click.y
        x = x.coerceIn(screen.x, maxOf(screen.x, screen.x + screen.width - width))
        y = y.coerceIn(screen.y, maxOf(screen.y, screen.y + screen.height - height))
        return Point(x, y)
    }
}
