package app.kadans.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import app.kadans.di.initKoin
import app.kadans.reminders.restoreDesktopReminders
import app.kadans.ui.App
import app.kadans.ui.brand.KadansIconPainter
import app.kadans.ui.brand.KadansSplash
import app.kadans.ui.rememberAppStrings

fun main() {
    initKoin()
    // Reminders ring from a timer in the app: what was scheduled before this start rings again (and offline).
    restoreDesktopReminders()
    application {
        // Closing the window keeps Kadans counting in the background (sessions advance and
        // notifications keep arriving); the tray brings it back. Without tray support the
        // close button must still quit, or the app would be stranded invisible.
        val traySupported = remember { java.awt.SystemTray.isSupported() }
        var windowVisible by remember { mutableStateOf(true) }
        val strings = rememberAppStrings()

        if (traySupported) {
            Tray(
                state = rememberTrayState(),
                icon = remember { KadansIconPainter(small = true) },
                tooltip = "Kadans",
                onAction = { windowVisible = true },
                menu = {
                    Item(strings.trayOpen, onClick = { windowVisible = true })
                    Item(strings.trayQuit, onClick = ::exitApplication)
                },
            )
        }

        Window(
            onCloseRequest = { if (traySupported) windowVisible = false else exitApplication() },
            visible = windowVisible,
            title = "Kadans",
            icon = remember { KadansIconPainter() },
            state = rememberWindowState(width = 480.dp, height = 800.dp),
        ) {
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
