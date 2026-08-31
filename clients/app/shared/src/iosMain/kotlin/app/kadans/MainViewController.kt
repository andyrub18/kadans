package app.kadans

import androidx.compose.ui.window.ComposeUIViewController
import app.kadans.di.initKoin
import app.kadans.ui.App

@Suppress("unused", "FunctionName") // called from Swift
fun MainViewController() = ComposeUIViewController {
    initKoin()
    App()
}
