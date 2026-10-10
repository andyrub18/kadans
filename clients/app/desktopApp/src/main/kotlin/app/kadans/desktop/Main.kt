package app.kadans.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import app.kadans.di.initKoin
import app.kadans.reminders.restoreDesktopReminders
import app.kadans.tray.StatusNotifierTray
import app.kadans.tray.TrayAction
import app.kadans.ui.App
import app.kadans.ui.brand.KadansIconPainter
import app.kadans.ui.brand.KadansSplash
import app.kadans.ui.currentAppStrings
import app.kadans.ui.rememberAppStrings
import java.awt.Desktop
import java.awt.SystemTray
import java.awt.desktop.AppReopenedListener
import java.awt.image.BufferedImage
import kotlinx.coroutines.channels.Channel

fun main() {
    // What the tray, a second start and the Dock ask of the window, from their own threads.
    val requests = Channel<TrayAction>(Channel.UNLIMITED)

    when (SingleInstance().claim(onShow = { requests.trySend(TrayAction.Open) })) {
        SingleInstance.Claim.Only -> Unit
        SingleInstance.Claim.AskedOther -> return println("Kadans is already running: its window is shown.")
        SingleInstance.Claim.OtherUnreachable ->
            return System.err.println("Kadans is already running but does not answer. Quit it from its tray icon, then start it again.")
    }

    initKoin()
    // Reminders ring from a timer in the app: what was scheduled before this start rings again (and offline).
    restoreDesktopReminders()

    // Linux desktops host the freedesktop tray (COSMIC, KDE Plasma, GNOME with AppIndicator); AWT's tray speaks only
    // XEmbed, which they no longer host. Elsewhere AWT's tray is the system's own.
    val linux = "linux" in System.getProperty("os.name").lowercase()
    val firstStrings = currentAppStrings()
    val linuxTray = if (!linux) null else StatusNotifierTray.start(
        title = "Kadans",
        icons = TRAY_ICON_SIZES.map(::trayIcon),
        openLabel = firstStrings.trayOpen,
        quitLabel = firstStrings.trayQuit,
        onAction = { requests.trySend(it) },
    )

    // macOS: clicking the Dock icon of a Kadans closed to the menu bar shows its window again.
    runCatching {
        if (Desktop.getDesktop().isSupported(Desktop.Action.APP_EVENT_REOPENED)) {
            Desktop.getDesktop().addAppEventListener(AppReopenedListener { requests.trySend(TrayAction.Open) })
        }
    }

    application {
        // Closing the window keeps Kadans counting in the background (sessions advance, reminders ring, notifications
        // arrive); the tray brings it back. Without any tray the close button must still quit, or the app would be
        // stranded invisible.
        val awtTray = remember { linuxTray == null && SystemTray.isSupported() }
        val hasTray = linuxTray != null || awtTray
        var windowVisible by remember { mutableStateOf(true) }
        var raised by remember { mutableIntStateOf(0) }
        val windowState = rememberWindowState(width = 480.dp, height = 800.dp)
        val strings = rememberAppStrings()

        LaunchedEffect(Unit) {
            for (request in requests) {
                when (request) {
                    TrayAction.Open -> {
                        windowVisible = true
                        windowState.isMinimized = false
                        raised++
                    }
                    TrayAction.Quit -> exitApplication()
                }
            }
        }
        LaunchedEffect(strings) { linuxTray?.setLabels(strings.trayOpen, strings.trayQuit) }
        DisposableEffect(Unit) { onDispose { linuxTray?.close() } }

        if (awtTray) {
            Tray(
                state = rememberTrayState(),
                icon = remember { KadansIconPainter(small = true) },
                tooltip = "Kadans",
                onAction = { requests.trySend(TrayAction.Open) },
                menu = {
                    Item(strings.trayOpen, onClick = { requests.trySend(TrayAction.Open) })
                    Item(strings.trayQuit, onClick = ::exitApplication)
                },
            )
        }

        Window(
            onCloseRequest = { if (hasTray) windowVisible = false else exitApplication() },
            visible = windowVisible,
            title = "Kadans",
            icon = remember { KadansIconPainter() },
            state = windowState,
        ) {
            // Asked for again while already open: in front of the other windows.
            LaunchedEffect(raised) { if (raised > 0) window.toFront() }
            // The splash plays over the app while it loads, once per launch (closing to the tray keeps it played).
            var splashing by remember { mutableStateOf(true) }
            Box(Modifier.fillMaxSize()) {
                App()
                AnimatedVisibility(visible = splashing, enter = EnterTransition.None, exit = fadeOut(tween(250))) {
                    KadansSplash(onFinished = { splashing = false })
                }
            }
        }
    }
}

/** The panel picks the size it draws: 16–32 px at 100 %, up to 64 on a scaled screen. */
private val TRAY_ICON_SIZES = listOf(16, 22, 24, 32, 48, 64)

/** The tray's mark (no hour marks, bolder strokes), drawn at [px] square. */
private fun trayIcon(px: Int): BufferedImage {
    val drawn = KadansIconPainter(small = true).toAwtImage(Density(1f), LayoutDirection.Ltr, Size(px.toFloat(), px.toFloat()))
    return BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB).apply {
        createGraphics().run {
            drawImage(drawn, 0, 0, px, px, null)
            dispose()
        }
    }
}
