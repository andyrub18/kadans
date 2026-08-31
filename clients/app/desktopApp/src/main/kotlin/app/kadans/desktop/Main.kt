package app.kadans.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.kadans.App

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Kadans",
        state = rememberWindowState(width = 480.dp, height = 800.dp),
    ) {
        App()
    }
}
