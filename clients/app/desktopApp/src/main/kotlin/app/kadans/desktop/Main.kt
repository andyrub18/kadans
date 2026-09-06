package app.kadans.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import app.kadans.di.initKoin
import app.kadans.ui.App
import app.kadans.ui.rememberAppStrings

fun main() {
    initKoin()
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
                icon = KadansDot,
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
            state = rememberWindowState(width = 480.dp, height = 800.dp),
        ) {
            App()
        }
    }
}

private val KadansDot = object : Painter() {
    override val intrinsicSize: Size = Size(16f, 16f)

    override fun DrawScope.onDraw() {
        drawCircle(Color(0xFF6750A4), radius = size.minDimension / 2, center = Offset(size.width / 2, size.height / 2))
    }
}
