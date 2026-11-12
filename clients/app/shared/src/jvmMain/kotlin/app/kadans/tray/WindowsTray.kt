package app.kadans.tray

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import app.kadans.ui.KadansTheme
import app.kadans.ui.brand.KadansIconPainter
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/**
 * Windows' tray icon, with [TrayMenu] on a right-click in place of Java's menu (ARCHITECTURE → "The desktop app: in the
 * tray, once"). A double-click on the icon, or on a reminder's balloon, does [onAction], as before. Reminders' balloons
 * find this icon (`SystemTray`'s first) and use it, as they used Compose's.
 */
@Composable
fun WindowsTray(openLabel: String, quitLabel: String, onAction: () -> Unit, onOpen: () -> Unit, onQuit: () -> Unit) {
    // Where the menu was asked for, in screen coordinates; null while it is closed.
    var clickedAt by remember { mutableStateOf<Point?>(null) }
    val action by rememberUpdatedState(onAction)

    DisposableEffect(Unit) {
        val tray = SystemTray.getSystemTray()
        // Compose's own tray icon draws the painter the same way: a 16-unit image whose sharper variants Windows takes
        // on a scaled screen.
        val image = KadansIconPainter(small = true).toAwtImage(Density(1f), LayoutDirection.Ltr, Size(16f, 16f))
        val icon = TrayIcon(image, "Kadans").apply {
            isImageAutoSize = true
            addActionListener {
                clickedAt = null
                action()
            }
            addMouseListener(object : MouseAdapter() {
                // Windows reports the right button as the popup trigger when it is released; other systems on press.
                override fun mousePressed(e: MouseEvent) = menuAsked(e)

                override fun mouseReleased(e: MouseEvent) = menuAsked(e)

                private fun menuAsked(e: MouseEvent) {
                    if (e.isPopupTrigger) clickedAt = e.locationOnScreen
                }
            })
        }
        runCatching { tray.add(icon) }
        onDispose { tray.remove(icon) }
    }

    clickedAt?.let { at ->
        // A new click opens a new menu where it happened.
        key(at) {
            TrayMenuWindow(
                at = at,
                openLabel = openLabel,
                quitLabel = quitLabel,
                onOpen = {
                    clickedAt = null
                    onOpen()
                },
                onQuit = {
                    clickedAt = null
                    onQuit()
                },
                onDismiss = { clickedAt = null },
            )
        }
    }
}

/**
 * The menu in a window of its own: no frame, a transparent background (the shadow falls in [TrayMenuLayout.SHADOW_ROOM]),
 * above every other window, and no button in the taskbar (a dialog). It closes as soon as it loses focus: a click
 * anywhere else, on the desktop or another window, as Windows' own menus do.
 */
@Composable
private fun TrayMenuWindow(
    at: Point,
    openLabel: String,
    quitLabel: String,
    onOpen: () -> Unit,
    onQuit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val room = TrayMenuLayout.SHADOW_ROOM
    val corner = remember(at) { TrayMenuLayout.place(at, usableScreenAt(at)) }
    val state = rememberDialogState(
        position = WindowPosition((corner.x - room).dp, (corner.y - room).dp),
        size = DpSize((TrayMenuLayout.WIDTH + 2 * room).dp, (TrayMenuLayout.HEIGHT + 2 * room).dp),
    )
    val dismiss by rememberUpdatedState(onDismiss)

    DialogWindow(
        onCloseRequest = onDismiss,
        state = state,
        title = "Kadans",
        undecorated = true,
        transparent = true,
        resizable = false,
    ) {
        DisposableEffect(window) {
            val leaving = object : WindowAdapter() {
                override fun windowLostFocus(e: WindowEvent) = dismiss()

                override fun windowDeactivated(e: WindowEvent) = dismiss()
            }
            window.isAlwaysOnTop = true
            window.addWindowFocusListener(leaving)
            window.addWindowListener(leaving)
            // In front and focused, so the keys reach it and a click elsewhere closes it. Windows lets the owner of a tray
            // icon take the foreground right after the person clicked that icon.
            window.toFront()
            window.requestFocus()
            onDispose {
                window.removeWindowFocusListener(leaving)
                window.removeWindowListener(leaving)
            }
        }
        KadansTheme {
            Box(Modifier.fillMaxSize().padding(room.dp), contentAlignment = Alignment.TopStart) {
                TrayMenu(openLabel, quitLabel, onOpen = onOpen, onQuit = onQuit, onDismiss = onDismiss)
            }
        }
    }
}

/** The usable part (the taskbar left out) of the screen showing [point]; the main screen's when none does. */
private fun usableScreenAt(point: Point): Rectangle {
    val screens = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration }
    val config = screens.firstOrNull { it.bounds.contains(point) } ?: screens.first()
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
    val bounds = config.bounds
    return Rectangle(
        bounds.x + insets.left,
        bounds.y + insets.top,
        bounds.width - insets.left - insets.right,
        bounds.height - insets.top - insets.bottom,
    )
}
